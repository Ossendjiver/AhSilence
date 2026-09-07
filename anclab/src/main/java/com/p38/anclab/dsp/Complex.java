package com.p38.anclab.dsp;

public record Complex(double re, double im) {
    public static final Complex ZERO = new Complex(0.0, 0.0);
    public Complex add(Complex other) { return new Complex(re + other.re, im + other.im); }
    public Complex subtract(Complex other) { return new Complex(re - other.re, im - other.im); }
    public Complex multiply(double scalar) { return new Complex(re * scalar, im * scalar); }
    public Complex multiply(Complex other) { return new Complex(re * other.re - im * other.im, re * other.im + im * other.re); }
    public Complex divide(Complex other) { double d=other.re*other.re+other.im*other.im; if(d<1e-18)return ZERO; return new Complex((re*other.re+im*other.im)/d,(im*other.re-re*other.im)/d); }
    public Complex negate() { return new Complex(-re,-im); }
    public Complex conjugate() { return new Complex(re,-im); }
    public double magnitudeSquared() { return re*re+im*im; }
    public double magnitude() { return Math.hypot(re,im); }
    public double phaseRadians() { return Math.atan2(im,re); }
    public Complex clampMagnitude(double maximum) { double m=magnitude(); return (m<=maximum||m==0.0)?this:multiply(maximum/m); }
    public static Complex polar(double magnitude,double phaseRadians){return new Complex(magnitude*Math.cos(phaseRadians),magnitude*Math.sin(phaseRadians));}
}
