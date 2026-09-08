#!/usr/bin/env python3
import argparse, csv, json, math, wave
from dataclasses import dataclass
from pathlib import Path
import numpy as np
from scipy.signal import butter, sosfilt

SR=48000
DECIMATION=96
ANALYSIS_RATE=500.0
RING_SAMPLES=1100
ANALYSIS_INTERVAL=50
SCAN_EVERY=5
MIN_AGE=1.0
STALE=2.5
MATCH_RADIUS=0.65
DETECT_FLOOR=-72.0
ADMISSION_FLOOR=-62.0
MIN_SEPARATION=1.25
DUP_RADIUS=1.0
TRACK_HALF=0.70
SEARCH_HALF=0.85
LANE_STALE=8.0

@dataclass
class Track:
    ident:int; first:float; last:float; hz:float; db:float; confirmations:int=0; seen:bool=False

@dataclass
class Lane:
    ident:str; anchor:float; hz:float; admitted:float; last_strong:float; db:float


def dbfs(a): return 20*math.log10(max(abs(a),1e-6))

def read_section(path,start,end):
    with wave.open(str(path),'rb') as w:
        assert w.getnchannels()==1 and w.getframerate()==SR and w.getsampwidth()==2
        w.setpos(int(start*SR)); raw=w.readframes(int((end-start)*SR))
    return np.frombuffer(raw,dtype='<i2').astype(np.float64)/32768.0

def preprocess(x):
    # Production analysis path: ~3 Hz DC rejection then 210 Hz LP, followed by /96 decimation.
    # scipy implementation is used only to supply the same 8-200 Hz discovery domain.
    sos=butter(2,210,btype='lowpass',fs=SR,output='sos')
    y=sosfilt(sos,x)
    # first-order 3 Hz HP equivalent to production DC blocker
    r=math.exp(-2*math.pi*3/SR)
    out=np.empty_like(y); px=0.0; py=0.0
    for i,v in enumerate(y):
        z=v-px+r*py; out[i]=z; px=v; py=z
    return out[DECIMATION-1::DECIMATION]

def peaks(window,max_peaks=12):
    n=len(window); desired=math.ceil(ANALYSIS_RATE/0.04); fft=1
    while fft<max(n,desired) and fft<32768: fft*=2
    while fft<n: fft*=2
    win=np.hanning(n); spec=np.fft.rfft(window*win,n=fft); scale=2.0/max(1.0,win.sum())
    amp=np.abs(spec)*scale; freqs=np.fft.rfftfreq(fft,1/ANALYSIS_RATE)
    sel=(freqs>=8)&(freqs<=200); vals=amp[sel]; fs=freqs[sel]
    med=np.median(vals); cand=[]
    for i in range(1,len(vals)-1):
        c=vals[i]
        if c<=vals[i-1] or c<vals[i+1] or c<=max(1e-5,med*1.6): continue
        l=math.log(vals[i-1]+1e-12); cc=math.log(c+1e-12); rr=math.log(vals[i+1]+1e-12)
        den=l-2*cc+rr; off=0 if abs(den)<1e-12 else max(-.5,min(.5,.5*(l-rr)/den))
        hz=fs[i]+off*(ANALYSIS_RATE/fft); cand.append((c,hz,dbfs(c)))
    cand.sort(reverse=True)
    chosen=[]
    for a,h,d in cand:
        if any(abs(h-h2)<MIN_SEPARATION for _,h2,_ in chosen): continue
        chosen.append((a,h,d))
        if len(chosen)>=max_peaks: break
    return chosen

def replay(x,start_time,lane_limit,persistent=True,admission_floor=ADMISSION_FLOOR):
    tracks=[]; lanes=[]; events=[]; next_id=1; analysis_count=0
    first34det=None; first34mature=None; first34admit=None; prop_presence=0.0
    # Approximate production tracker with conservative exponential smoothing inside match radius.
    def update_tracks(ds,t):
        nonlocal next_id, first34det, first34mature
        for tr in tracks: tr.seen=False
        ready=[]
        for _,hz,d in ds:
            if d<DETECT_FLOOR: continue
            if 33.0<=hz<=36.0 and first34det is None: first34det=t
            best=None; dist=1e9
            for tr in tracks:
                dd=abs(tr.hz-hz)
                if not tr.seen and dd<=MATCH_RADIUS and dd<dist: best=tr; dist=dd
            if best is None:
                best=Track(next_id,t,t,hz,d); next_id+=1; tracks.append(best)
            best.hz=0.7*best.hz+0.3*hz; best.db=d; best.confirmations+=1; best.last=t; best.seen=True
        tracks[:]=[tr for tr in tracks if t-tr.last<=STALE]
        for tr in tracks:
            if tr.confirmations>=3 and t-tr.first>=MIN_AGE:
                if 33.0<=tr.hz<=36.0 and first34mature is None: first34mature=t
                ready.append(tr)
                if not persistent: tr.confirmations=-10**9
        ready.sort(key=lambda tr:tr.db,reverse=True)
        return ready
    ring=[]
    for idx,v in enumerate(x):
        ring.append(v)
        if len(ring)>RING_SAMPLES: ring.pop(0)
        if len(ring)<300 or (idx+1)%ANALYSIS_INTERVAL: continue
        t=start_time+(idx+1)/ANALYSIS_RATE; analysis_count+=1
        # lane tracking/expiry against local spectral search
        ps=peaks(np.asarray(ring),12)
        if any(33<=h<=36 and d>=admission_floor for _,h,d in ps): prop_presence+=ANALYSIS_INTERVAL/ANALYSIS_RATE
        for ln in lanes:
            nearby=[p for p in ps if abs(p[1]-ln.hz)<=SEARCH_HALF]
            if nearby:
                _,h,d=max(nearby,key=lambda q:q[0]); ln.hz=max(ln.anchor-TRACK_HALF,min(ln.anchor+TRACK_HALF,0.7*ln.hz+0.3*h)); ln.db=d
                if d>-76: ln.last_strong=t
        expired=[ln for ln in lanes if t-ln.last_strong>LANE_STALE]
        for ln in expired:
            events.append((t,'expire',ln.ident,ln.hz,ln.db,len(lanes)-1)); lanes.remove(ln)
        if analysis_count%SCAN_EVERY: continue
        ready=update_tracks(ps,t)
        for tr in ready:
            if tr.db<admission_floor: continue
            if any(abs(ln.hz-tr.hz)<DUP_RADIUS for ln in lanes): continue
            if len(lanes)>=lane_limit: continue
            ident=f'broad-{round(tr.hz*2)/2:.1f}'
            ln=Lane(ident,tr.hz,tr.hz,t,t,tr.db); lanes.append(ln)
            events.append((t,'admit',ident,tr.hz,tr.db,len(lanes)))
            if 33<=tr.hz<=36 and first34admit is None: first34admit=t
    return {
      'lane_limit':lane_limit,'persistent':persistent,'admission_floor_dbfs':admission_floor,
      'admissions':sum(e[1]=='admit' for e in events),'expirations':sum(e[1]=='expire' for e in events),
      'first_34_detection_s':first34det,'first_34_mature_s':first34mature,'first_34_admission_s':first34admit,
      'prop_33_36_presence_s':round(prop_presence,1),'final_lanes':[round(l.hz,3) for l in lanes],
      'events':events
    }

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('wav'); ap.add_argument('--out',default='p38-v059-section-results'); args=ap.parse_args()
    out=Path(args.out); out.mkdir(parents=True,exist_ok=True)
    sections=[(20,200),(20,80),(80,140),(140,200)]
    rows=[]; allj={}
    for a,b in sections:
        sig=preprocess(read_section(args.wav,a,b))
        configs=[('v059_bb_off',10,True,-62.0),('v059_bb_on',6,True,-62.0)]
        if (a,b)==(20,200): configs.append(('legacy_6slot_one_shot',6,False,-72.0))
        for name,lim,pers,floor in configs:
            r=replay(sig,a,lim,pers,floor); key=f'{a}-{b}_{name}'; allj[key]={k:v for k,v in r.items() if k!='events'}
            rows.append({'section':f'{a}-{b}','mode':name,**{k:v for k,v in r.items() if k not in ('events','final_lanes')},'final_lanes':';'.join(map(str,r['final_lanes']))})
            with open(out/f'{key}_events.csv','w',newline='') as f:
                w=csv.writer(f); w.writerow(['time_s','event','id','hz','dbfs','lane_count']); w.writerows(r['events'])
    with open(out/'summary.csv','w',newline='') as f:
        w=csv.DictWriter(f,fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)
    (out/'summary.json').write_text(json.dumps(allj,indent=2))
    print(json.dumps(allj,indent=2))
if __name__=='__main__': main()
