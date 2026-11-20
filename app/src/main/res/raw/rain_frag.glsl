#version 300 es
// Amepaper — rain on glass.
// Procedural raindrops (sliding drops with static trails + condensation droplets),
// fogged glass and lens refraction, drawn over the current wallpaper.
// Two image layers (A and B) are kept so the app can cross-fade between wallpapers.
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTexA;   // sharp image
uniform sampler2D uFogA;   // blurred copy (fog + letterbox fill)
uniform vec4 uRectA;       // where the image sits on screen (x0, y0, x1, y1), 0..1, top-left origin
uniform vec4 uCoverA;      // rect where the image would cover the screen (for letterbox fill)
uniform vec4 uFxA;         // x brightness (1 = original), y vignette, z grayscale, w rain visibility

uniform sampler2D uTexB;
uniform sampler2D uFogB;
uniform vec4 uRectB;
uniform vec4 uCoverB;
uniform vec4 uFxB;
uniform float uMix;        // 0 = layer A only, 1 = layer B only

uniform vec2 uRes;         // surface size in px
uniform float uTime;       // seconds (already multiplied by speed)
uniform float uRain;       // sliding drops 0..1
uniform float uDrops;      // condensation droplets 0..1
uniform float uSize;       // drop size multiplier
uniform float uFog;        // fogged glass 0..1
uniform float uRefr;       // refraction 0..1
uniform float uTrail;      // trail clearing 0..1

#define TAU 6.28318530718

// Hash without sine (Dave Hoskins, MIT)
float h11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float h21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 h22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }

// e = offset from drop centre in drop radii, m = coverage, r = radius (in the layer's units)
struct Drop { vec2 e; float m; float r; };

Drop noDrop() { Drop d; d.e = vec2(0.0); d.m = 0.0; d.r = 0.0; return d; }

// Condensation droplets: one per cell, they grow, sit and evaporate.
Drop droplets(vec2 q, float t, float density, float seed) {
    vec2 id = floor(q);
    vec2 f = fract(q) - 0.5;
    float n = h21(id + seed * 1.7 + 3.1);
    if (n > density) return noDrop();
    vec2 h = h22(id + seed);
    vec2 c = (h - 0.5) * 0.45;
    float life = fract(t * (0.015 + 0.04 * h.y) + n * 7.0);
    float grow = smoothstep(0.0, 0.15, life) * (1.0 - smoothstep(0.85, 1.0, life));
    float r = (0.07 + 0.16 * h.x * h.x) * sqrt(grow);
    if (r < 0.002) return noDrop();
    vec2 off = f - c;
    Drop d;
    d.e = off / r;
    d.m = 1.0 - smoothstep(0.82, 1.0, length(d.e));
    d.r = r;
    return d;
}

// Sliding drops: columns move down with a stick–slip motion. The trail is defined in
// glass space, so it stays put and fades after the drop has passed.
Drop sliding(vec2 q, float t, float amount, float seed, out float trail, out Drop tdrop) {
    trail = 0.0;
    tdrop = noDrop();
    const float W = 0.42;   // column width
    const float H = 2.2;    // cell height (one drop per cell)
    float col = floor(q.x / W);
    float hc = h11(col * 7.13 + seed);
    if (hc > amount) return noDrop();
    float hc2 = h11(col * 3.71 + seed + 11.0);
    float hc3 = h11(col * 1.93 + seed + 23.0);
    float v = mix(0.16, 0.40, hc2);
    float rate = mix(0.12, 0.40, hc3);
    // position = v * (t + k sin(..)/(2 pi rate)) -> speed v*(1 + k cos(..)) never goes backwards
    float P = v * (t + 0.88 * sin(TAU * rate * t + hc * TAU) / (TAU * rate)) + hc * 13.0;
    float yl = q.y - P;
    float cj = floor(yl / H);
    float fy = yl - cj * H;                       // 0 at top of the cell
    vec2 hj = h22(vec2(col, cj) + seed);
    if (h21(vec2(cj, col) + seed + 5.0) > 0.8) return noDrop();
    float r = mix(0.045, 0.08, hj.x);
    float yc = H * mix(0.55, 0.85, hj.y);
    float baseX = (col + 0.5) * W + (hj.x - 0.5) * W * 0.22;
    float xc = baseX + 0.022 * sin(q.y * 6.0 + hc * TAU) + 0.01 * sin(q.y * 17.0 + hj.y * TAU);

    // the drop, a little taller than wide with a narrower top
    vec2 off = vec2(q.x - xc, fy - yc);
    float squeeze = mix(1.0, 0.82, 1.0 - smoothstep(-r * 1.2, 0.0, off.y));
    vec2 e = vec2(off.x / (r * squeeze), off.y / (r * 1.18));
    Drop d;
    d.e = e;
    d.m = 1.0 - smoothstep(0.85, 1.0, length(e));
    d.r = r;

    // trail above the drop
    float above = yc - fy;
    float L = mix(0.5, 1.1, hj.y);
    float tr = step(0.0, above) * exp(-above / L) * smoothstep(0.0, 0.3 * H, fy);
    float tw = r * mix(0.45, 0.8, tr);
    trail = tr * (1.0 - smoothstep(tw * 0.6, tw, abs(q.x - xc)));

    // little droplets left behind along the trail (fixed to the glass)
    const float TH = 0.1;
    float ty = q.y / TH;
    float tid = floor(ty);
    float tfy = fract(ty) - 0.5;
    float th2 = h21(vec2(tid, col) + seed + 9.0);
    float tjx = (h11(tid * 3.1 + col * 1.7 + seed) - 0.5) * r * 0.8;
    float tr2 = r * mix(0.16, 0.34, th2) * sqrt(tr) * step(0.3, th2) * step(r * 1.3, above);
    if (tr2 > 0.002) {
        vec2 toff = vec2(q.x - xc - tjx, tfy * TH);
        tdrop.e = toff / tr2;
        tdrop.m = 1.0 - smoothstep(0.8, 1.0, length(tdrop.e));
        tdrop.r = tr2;
    }
    return d;
}

// Keep whichever drop covers the pixel the most; s converts the layer's units to screen-height units.
void pick(inout Drop best, inout float bestScale, Drop d, float s) {
    if (d.m > best.m) { best = d; bestScale = s; }
}

vec3 sampleImage(sampler2D tex, sampler2D fogTex, vec4 rect, vec4 cover, vec2 p, float fog) {
    vec2 uv = (p - rect.xy) / max(rect.zw - rect.xy, vec2(1e-5));
    vec2 cuv = clamp((p - cover.xy) / max(cover.zw - cover.xy, vec2(1e-5)), 0.0, 1.0);
    vec3 bg = texture(fogTex, cuv).rgb * 0.6;
    float inside = step(0.0, uv.x) * step(0.0, uv.y) * step(uv.x, 1.0) * step(uv.y, 1.0);
    vec2 iuv = clamp(uv, 0.0, 1.0);
    vec3 sharp = textureLod(tex, iuv, 0.0).rgb;
    vec3 blur = texture(fogTex, iuv).rgb;
    vec3 fogged = mix(sharp, blur * 0.97 + 0.035, fog);
    return mix(bg, fogged, inside);
}

vec3 applyFx(vec3 c, vec2 s, vec4 fx) {
    float g = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c, vec3(g), fx.z);
    vec2 v = (s - 0.5) * 2.0;
    c *= 1.0 - fx.y * smoothstep(0.35, 1.5, length(v));
    return c * fx.x;
}

vec3 shade(sampler2D tex, sampler2D fogTex, vec4 rect, vec4 cover, vec4 fx, vec2 s,
           Drop best, vec2 disp, float fogMask, float aspect) {
    float vis = fx.w;
    vec3 base = sampleImage(tex, fogTex, rect, cover, s, uFog * fogMask * vis);
    if (best.m * vis > 0.001) {
        vec3 refr = sampleImage(tex, fogTex, rect, cover, clamp(s + disp, vec2(0.0), vec2(1.0)), 0.0);
        float nd = length(best.e);
        // darker rim (heavier at the bottom) and a soft highlight at the top-left
        float rim = smoothstep(0.5, 1.0, nd) * (0.22 + 0.18 * clamp(best.e.y, 0.0, 1.0));
        refr *= 1.0 - rim;
        float spec = 1.0 - smoothstep(0.0, 0.32, length(best.e - vec2(-0.32, -0.42)));
        refr += spec * 0.22;
        base = mix(base, refr, best.m * vis);
    }
    return applyFx(base, s, fx);
}

void main() {
    vec2 s = vec2(vUv.x, 1.0 - vUv.y);
    float aspect = uRes.x / uRes.y;
    vec2 b = vec2(s.x * aspect, s.y);          // screen-height units
    float t = uTime;
    float size = max(uSize, 0.2);

    Drop best = noDrop();
    float bestScale = 1.0;
    float trail = 0.0;

    if (uRain > 0.001) {
        float S1 = 3.0 / size;
        float S2 = 5.2 / size;
        float tr1; float tr2;
        Drop td1; Drop td2;
        Drop d1 = sliding(b * S1, t, uRain, 0.0, tr1, td1);
        Drop d2 = sliding(b * S2 + vec2(0.21, 0.0), t * 1.08, uRain * 0.85, 37.0, tr2, td2);
        trail = max(tr1, tr2);
        pick(best, bestScale, td2, S2);
        pick(best, bestScale, td1, S1);
        pick(best, bestScale, d2, S2);
        pick(best, bestScale, d1, S1);
    }
    if (uDrops > 0.001) {
        float wipe = 1.0 - smoothstep(0.02, 0.25, trail);
        float S3 = 24.0 / size;
        float S4 = 52.0 / size;
        Drop s1 = droplets(b * S3, t, uDrops, 1.0);
        Drop s2 = droplets(b * S4, t * 1.3, uDrops * 0.9, 17.0);
        s1.m *= wipe;
        s2.m *= wipe;
        if (best.m < 0.5) {
            Drop prev = best; float prevScale = bestScale;
            best = noDrop();
            pick(best, bestScale, s2, S4);
            pick(best, bestScale, s1, S3);
            if (prev.m >= best.m) { best = prev; bestScale = prevScale; }
        }
    }

    // lens: a drop shows a small, upside-down view of what is behind it
    float k = mix(1.2, 5.5, uRefr);
    vec2 offB = best.e * best.r / bestScale;        // pixel offset from drop centre, screen-height units
    vec2 dispB = -offB * (1.0 + k * (0.75 + 0.25 * dot(best.e, best.e)));
    vec2 disp = vec2(dispB.x / aspect, dispB.y);

    float fogMask = 1.0 - clamp(trail * uTrail * 1.4, 0.0, 1.0);

    vec3 col;
    if (uMix <= 0.001) {
        col = shade(uTexA, uFogA, uRectA, uCoverA, uFxA, s, best, disp, fogMask, aspect);
    } else if (uMix >= 0.999) {
        col = shade(uTexB, uFogB, uRectB, uCoverB, uFxB, s, best, disp, fogMask, aspect);
    } else {
        vec3 a = shade(uTexA, uFogA, uRectA, uCoverA, uFxA, s, best, disp, fogMask, aspect);
        vec3 c = shade(uTexB, uFogB, uRectB, uCoverB, uFxB, s, best, disp, fogMask, aspect);
        col = mix(a, c, smoothstep(0.0, 1.0, uMix));
    }
    fragColor = vec4(col, 1.0);
}
