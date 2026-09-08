package com.p38.anclab.sensors;

/** Cross-spectral coherence / transfer-strength scoring for future sensor-primary ANC admission. */
public final class SensorCoherenceScorer {
    private SensorCoherenceScorer(){}
    public record Result(double frequencyHz,double coherence,double transferGain,double admissionScore,int segments){}

    public static Result scoreAtFrequency(float[] reference,float[] error,double sampleRate,double frequencyHz,int segmentLength,double prominenceDb,long previewMarginUs){
        if(reference==null||error==null||sampleRate<=0||frequencyHz<=0||segmentLength<64)throw new IllegalArgumentException("invalid coherence inputs");
        int n=Math.min(reference.length,error.length),segments=n/segmentLength;if(segments<3)return new Result(frequencyHz,0,0,0,segments);
        double sxx=0,syy=0,sxr=0,sxi=0;
        for(int s=0;s<segments;s++){
            double xr=0,xi=0,yr=0,yi=0,wSum=0;int base=s*segmentLength;
            for(int i=0;i<segmentLength;i++){
                double w=0.5-0.5*Math.cos(2*Math.PI*i/(segmentLength-1));double a=-2*Math.PI*frequencyHz*i/sampleRate;
                double c=Math.cos(a),sn=Math.sin(a);double x=reference[base+i]*w,y=error[base+i]*w;
                xr+=x*c;xi+=x*sn;yr+=y*c;yi+=y*sn;wSum+=w;
            }
            double norm=Math.max(1e-12,wSum);xr/=norm;xi/=norm;yr/=norm;yi/=norm;
            sxx+=xr*xr+xi*xi;syy+=yr*yr+yi*yi;
            // X * conj(Y)
            sxr+=xr*yr+xi*yi;sxi+=xi*yr-xr*yi;
        }
        sxx/=segments;syy/=segments;sxr/=segments;sxi/=segments;
        double coherence=(sxr*sxr+sxi*sxi)/Math.max(1e-18,sxx*syy);coherence=Math.max(0,Math.min(1,coherence));
        double gain=Math.sqrt(Math.max(0,syy)/Math.max(1e-18,sxx));
        double prominenceWeight=Math.max(0,Math.min(1,(prominenceDb-4.0)/16.0));
        double previewWeight=previewMarginUs>0?1.0:0.65; // periodic signals can remain useful with negative preview.
        double score=coherence*prominenceWeight*previewWeight;
        return new Result(frequencyHz,coherence,gain,score,segments);
    }
}
