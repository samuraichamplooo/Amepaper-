#version 300 es
// Amepaper — rain on glass (v2, tuned against Rain Paper).
// The wallpaper is seen through wet glass: softly blurred, covered in small irregular droplets
// that act as lenses (sharp, upside-down view of the picture, dark outline, bright highlight),
// plus drips that slide down and leave clear, dotted trails.
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTexA;   // sharp image
uniform sampler2D uMidA;   // lightly blurred copy
uniform sampler2D uFogA;   // heavily blurred copy (also fills letterbox bars)
uniform vec4 uRectA;       // image rect on screen (x0, y0, x1, y1), 0..1, top-left origin
uniform vec4 uCoverA;      // rect where the image would cover the screen
uniform vec4 uFxA;         // x brightness, y vignette, z grayscale, w rain visibility

uniform sampler2D uTexB;
uniform sampler2D uMidB;
uniform sampler2D uFogB;
uniform vec4 uRectB;
uniform vec4 uCoverB;
uniform vec4 uFxB;
uniform float uMix;        // 0 = layer A, 1 = layer B

uniform vec2 uRes;         // surface size, px
uniform float uTime;       // seconds (already multiplied by speed)
uniform float uRain;       // sliding drops 0..1
uniform float uDrops;      // droplets on the glass 0..1
uniform float uSize;       // drop size multiplier
uniform float uFog;        // glass blur 0..1
uniform float uRefr;       // refraction 0..1
uniform float uTrail;      // how clear the trails are 0..1
uniform float uImpact;     // raindrops hitting the glass 0..1
uniform float uFall;       // rain falling outside the window 0..1

#define TAU 6.28318530718

// Hash without sine (Dave Hoskins, MIT)
float h11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float h21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 h22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }

// e = offset from the drop's centre in (shape-corrected) radii, m = coverage,
// r = radius in screen-height units
struct Drop { vec2 e; float m; float r; };

Drop noDrop() { Drop d; d.e = vec2(0.0); d.m = 0.0; d.r = 0.0; return d; }

// Distance in radii to an irregular, slightly bottom-heavy droplet outline.
float blob(vec2 off, float r, vec2 h) {
    float ang = (h.x - 0.5) * 1.2;                       // slight random tilt
    float ca = cos(ang);
    float sa = sin(ang);
    vec2 o = vec2(ca * off.x - sa * off.y, sa * off.x + ca * off.y);
    o.x *= mix(0.8, 1.2, h.y);                           // some wider, some narrower
    o.y *= o.y > 0.0 ? 0.85 : 1.12;                      // water gathers at the bottom
    float a = atan(o.y, o.x);
    float wob = 1.0 + 0.07 * sin(2.0 * a + h.x * TAU) + 0.05 * sin(3.0 * a + h.y * TAU) + 0.025 * sin(5.0 * a + h.x * 17.0);
    return length(o) / (r * wob);
}

// Two droplets that touched and merged: smooth union of two blobs.
float blob2(vec2 off, float r, vec2 h, out vec2 eOff) {
    float d1 = blob(off, r, h);
    eOff = off;
    if (h.y > 0.45) return d1;
    float a = h.x * TAU;
    vec2 lobe = vec2(cos(a), sin(a) * 0.6 + 0.4) * r * 0.95;
    float r2 = r * mix(0.45, 0.75, h.y * 2.0);
    float d2 = blob(off - lobe, r2, h.yx);
    float k = 0.25;
    float hh = clamp(0.5 + 0.5 * (d2 - d1) / k, 0.0, 1.0);
    float d = mix(d2, d1, hh) - k * hh * (1.0 - hh);
    if (d2 < d1) eOff = (off - lobe) * (r / r2);
    return d;
}

Drop makeDrop(vec2 off, float d, float r, float scale) {
    Drop dr;
    float len = max(length(off), 1e-5);
    dr.e = off / len * d;
    float aa = clamp(1.5 / max(r / scale * uRes.y, 1.0), 0.03, 0.5);
    dr.m = 1.0 - smoothstep(1.0 - aa, 1.0, d);
    dr.r = r / scale;
    return dr;
}

// Droplets sitting on the glass. Each grid is rotated so no rows show, and every droplet
// lands somewhere new each time it dries up and comes back.
Drop droplets(vec2 b, float S, float ang, float t, float density, float seed) {
    float ca = cos(ang);
    float sa = sin(ang);
    vec2 q = vec2(ca * b.x - sa * b.y, sa * b.x + ca * b.y) * S;
    vec2 id = floor(q);
    vec2 f = fract(q) - 0.5;
    float n = h21(id + seed * 1.7 + 3.1);
    if (n > density) return noDrop();
    float rate = 0.008 + 0.025 * h21(id + seed + 5.3);
    float p = t * rate + n * 7.0;
    float k = floor(p);
    float life = p - k;
    vec2 h = h22(id + seed + k * 3.17);
    vec2 c = (h - 0.5) * 0.26;
    float age = life / rate;                                   // seconds since it landed
    float pop = smoothstep(0.0, 0.12, age);
    float wob = 1.0 + 0.15 * exp(-age * 14.0) * sin(age * 55.0);
    float fade = 1.0 - smoothstep(0.85, 1.0, life);
    if (fade < 0.02) return noDrop();
    float r = (0.06 + 0.13 * h.x * h.x) * (0.4 + 0.6 * pop) * wob * sqrt(fade);
    vec2 off = f - c;
    vec2 offW = vec2(ca * off.x + sa * off.y, -sa * off.x + ca * off.y);   // gravity still points down
    vec2 eOff;
    float d = blob2(offW, r, h22(id + seed + 11.0 + k), eOff);
    if (d > 1.2) return noDrop();
    return makeDrop(eOff, d, r, S);
}

// Raindrops hitting the glass: a drop lands at a random spot, pops in with a wobble,
// throws out a few tiny droplets and a quick ripple, then slowly dries up.
Drop impacts(vec2 b, float S, float t, float amount, float seed, out vec3 ring) {
    ring = vec3(0.0);
    vec2 q = b * S;
    vec2 id = floor(q);
    vec2 f = fract(q) - 0.5;
    float n = h21(id + seed);
    if (n > amount) return noDrop();
    float rate = mix(0.05, 0.14, h21(id + seed + 2.7));
    float p = t * rate + n * 11.0;
    float k = floor(p);
    float life = p - k;
    float age = life / rate;
    vec2 h = h22(id + k * 1.73 + seed);
    vec2 h2 = h22(id + k * 2.31 + seed + 4.0);
    vec2 c = (h - 0.5) * 0.12;
    float r0 = mix(0.09, 0.15, h2.x);
    float pop = smoothstep(0.0, 0.07, age);
    float wob = 1.0 + 0.22 * exp(-age * 12.0) * sin(age * 70.0);
    float fade = 1.0 - smoothstep(0.7, 1.0, life);
    float r = r0 * (0.35 + 0.65 * pop) * wob * sqrt(fade);
    vec2 off = f - c;

    if (age < 0.35) {
        float R = r0 * (1.0 + age * 5.0);
        float w = r0 * 0.4;
        float len = length(off);
        float a = 1.0 - age / 0.35;
        float band = 1.0 - smoothstep(0.0, w, abs(len - R));
        ring = vec3(off / max(len, 1e-4), band * a * a);
    }

    Drop best = noDrop();
    if (fade > 0.02) {
        vec2 eOff;
        float d = blob2(off, r, h2, eOff);
        if (d < 1.2) best = makeDrop(eOff, d, r, S);
    }
    float fly = smoothstep(0.0, 0.05, age);
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        vec2 hs = h22(id + k * 5.1 + fi * 13.7 + seed);
        if (hs.y < 0.3) continue;
        float a = hs.x * TAU;
        float dist = r0 * mix(1.5, 2.5, hs.y) * fly;
        vec2 sc = c + vec2(cos(a), sin(a)) * dist;
        float sr = r0 * mix(0.14, 0.28, fract(hs.x * 7.0 + hs.y)) * sqrt(fade) * fly;
        if (sr < 0.003) continue;
        vec2 so = f - sc;
        float sd = blob(so, sr, hs);
        if (sd < 1.2) {
            Drop sp = makeDrop(so, sd, sr, S);
            if (sp.m > best.m) best = sp;
        }
    }
    return best;
}

// Rain falling outside the window: thin slanted streaks at three depths.
float fallingRain(vec2 b, float t, float amount) {
    float acc = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float depth = fi / 2.0;
        vec2 p = b;
        p.x += p.y * 0.14;
        float cols = mix(14.0, 34.0, depth);
        float rows = mix(1.4, 2.6, depth);
        float v = mix(2.4, 1.5, depth);
        float col = floor(p.x * cols);
        vec2 q = vec2(p.x * cols, p.y * rows - t * v * rows + h11(col + fi * 31.0) * 10.0);
        vec2 id = floor(q);
        vec2 f = fract(q);
        float h = h21(id + fi * 7.3);
        if (h > amount) continue;
        vec2 hh = h22(id + fi * 3.1);
        float x = 0.2 + 0.6 * hh.x;
        float len = mix(0.25, 0.55, hh.y);
        float y0 = (1.0 - len) * fract(h * 13.0);
        float px = abs(f.x - x) / cols * uRes.y;
        float wpx = mix(1.4, 0.8, depth);
        float line = 1.0 - smoothstep(wpx * 0.5, wpx * 0.5 + 1.0, px);
        float along = (f.y - y0) / len;
        float seg = step(0.0, along) * step(along, 1.0) * smoothstep(0.0, 0.7, along);
        acc += line * seg * mix(0.2, 0.09, depth);
    }
    return acc;
}

// Drips sliding down with a stick–slip motion. Trails are fixed to the glass and fade.
Drop sliding(vec2 b, float S, float t, float amount, float seed, out float trail, out Drop tdrop) {
    trail = 0.0;
    tdrop = noDrop();
    vec2 q = b * S;
    const float W = 0.34;
    float col = floor(q.x / W);
    float hc = h11(col * 7.13 + seed);
    if (hc > amount) return noDrop();
    float hc2 = h11(col * 3.71 + seed + 11.0);
    float hc3 = h11(col * 1.93 + seed + 23.0);
    float H = mix(1.9, 3.0, h11(col * 5.37 + seed + 41.0));
    float v = mix(0.25, 0.65, hc2);
    float rate = mix(0.15, 0.5, hc3);
    float P = v * (t + 0.9 * sin(TAU * rate * t + hc * TAU) / (TAU * rate)) + hc * 13.0;
    float yl = q.y - P;
    float cj = floor(yl / H);
    float fy = yl - cj * H;
    vec2 hj = h22(vec2(col, cj) + seed);
    if (h21(vec2(cj, col) + seed + 5.0) > 0.7) return noDrop();
    float r = mix(0.035, 0.06, hj.x);
    float yc = H * mix(0.6, 0.88, hj.y);
    float baseX = (col + 0.5) * W + (h11(cj * 2.9 + col + seed) - 0.5) * W * 0.48;
    float xc = baseX + 0.016 * sin(q.y * 7.0 + hc * TAU) + 0.008 * sin(q.y * 19.0 + hj.y * TAU);

    // teardrop: round bottom, tail tapering upward
    vec2 off = vec2(q.x - xc, fy - yc);
    float up = 1.0 - smoothstep(-r * 2.2, 0.0, off.y);           // 0 at centre, 1 high on the tail
    float wx = r * mix(1.0, 0.45, up);
    float wy = off.y < 0.0 ? r * 2.0 : r * 0.95;
    vec2 e = vec2(off.x / wx, off.y / wy);
    float d = length(e);
    Drop dr;
    dr.e = e;
    float aa = clamp(1.5 / max(r / S * uRes.y, 1.0), 0.03, 0.5);
    dr.m = 1.0 - smoothstep(1.0 - aa, 1.0, d);
    dr.r = r / S;

    // the wet trail above the drip
    float above = yc - fy;
    float L = mix(0.6, 1.4, hj.y);
    float tr = step(0.0, above) * exp(-above / L) * smoothstep(0.0, 0.3 * H, fy);
    float tw = r * mix(0.35, 0.65, tr);
    trail = tr * (1.0 - smoothstep(tw * 0.5, tw, abs(q.x - xc)));

    // tiny beads left in the trail
    const float TH = 0.075;
    float ty = q.y / TH;
    float tid = floor(ty);
    float tfy = fract(ty) - 0.5;
    float th2 = h21(vec2(tid, col) + seed + 9.0);
    float tjx = (h11(tid * 3.1 + col * 1.7 + seed) - 0.5) * r * 0.7;
    float tr2 = r * mix(0.18, 0.4, th2) * sqrt(tr) * step(0.35, th2) * step(r * 1.4, above);
    if (tr2 > 0.002) {
        vec2 toff = vec2(q.x - xc - tjx, tfy * TH);
        float td = blob(toff, tr2, h22(vec2(tid, col) + 3.0));
        tdrop = makeDrop(toff, td, tr2, S);
    }
    return dr;
}

void pick(inout Drop best, Drop d) {
    if (d.m > best.m) best = d;
}

// Glass blur: 0 = sharp, 0.5 = light blur, 1 = heavy blur.
vec3 glass(sampler2D tex, sampler2D mid, sampler2D fog, vec2 uv, float blur) {
    vec3 s = textureLod(tex, uv, 0.0).rgb;
    if (blur <= 0.002) return s;
    vec3 m = texture(mid, uv).rgb;
    if (blur < 0.5) return mix(s, m, blur * 2.0);
    return mix(m, texture(fog, uv).rgb, (blur - 0.5) * 2.0);
}

vec3 sampleImage(sampler2D tex, sampler2D mid, sampler2D fog, vec4 rect, vec4 cover, vec2 p, float blur) {
    vec2 uv = (p - rect.xy) / max(rect.zw - rect.xy, vec2(1e-5));
    float inside = step(0.0, uv.x) * step(0.0, uv.y) * step(uv.x, 1.0) * step(uv.y, 1.0);
    vec3 img = glass(tex, mid, fog, clamp(uv, 0.0, 1.0), blur);
    if (inside > 0.5) return img;
    vec2 cuv = clamp((p - cover.xy) / max(cover.zw - cover.xy, vec2(1e-5)), 0.0, 1.0);
    return texture(fog, cuv).rgb * 0.6;
}

vec3 applyFx(vec3 c, vec2 s, vec4 fx) {
    float g = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c, vec3(g), fx.z);
    vec2 v = (s - 0.5) * 2.0;
    c *= 1.0 - fx.y * smoothstep(0.35, 1.5, length(v));
    return c * fx.x;
}

vec3 shade(sampler2D tex, sampler2D mid, sampler2D fog, vec4 rect, vec4 cover, vec4 fx, vec2 s,
           Drop best, vec2 disp, float clearAmt, vec2 ringDisp, float ringAmt, float fall) {
    float vis = fx.w;
    float blur = uFog * vis * (1.0 - clearAmt);
    vec3 base = sampleImage(tex, mid, fog, rect, cover, clamp(s + ringDisp * vis, vec2(0.0), vec2(1.0)), blur);
    base += vec3(0.9, 0.93, 1.0) * fall * vis;
    base *= 1.0 + ringAmt * 0.12 * vis;
    float m = best.m * vis;
    if (m > 0.001) {
        // inside a drop the picture is sharp (it's a lens focused on the scene)
        vec3 refr = sampleImage(tex, mid, fog, rect, cover, clamp(s + disp, vec2(0.0), vec2(1.0)), 0.0);
        float nd = clamp(length(best.e), 0.0, 1.0);
        float hgt = sqrt(max(0.0, 1.0 - nd * nd));
        vec3 N = normalize(vec3(best.e * 1.25, hgt + 0.1));
        vec3 L = normalize(vec3(-0.3, -0.8, 0.9));                 // light from above (y points down)
        vec3 Hh = normalize(L + vec3(0.0, 0.0, 1.0));
        float spec = pow(max(dot(N, Hh), 0.0), 120.0);
        float rim = smoothstep(0.3, 1.0, nd);
        vec3 c = refr * (1.0 - rim * (0.62 + 0.2 * clamp(best.e.y + 0.3, 0.0, 1.0)));
        // light focused through the drop brightens its lower inside
        float caustic = (1.0 - smoothstep(0.0, 0.5, length(best.e - vec2(0.1, 0.45)))) * 0.12;
        c += refr * caustic;
        c += spec * 0.45;
        base = mix(base, c, m);
    }
    return applyFx(base, s, fx);
}

void main() {
    vec2 s = vec2(vUv.x, 1.0 - vUv.y);
    float aspect = uRes.x / uRes.y;
    vec2 b = vec2(s.x * aspect, s.y);   // screen-height units
    float t = uTime;
    float size = max(uSize, 0.2);

    Drop best = noDrop();
    float trail = 0.0;

    if (uRain > 0.001) {
        float tr1; float tr2;
        Drop td1; Drop td2;
        Drop d1 = sliding(b, 5.0 / size, t, uRain, 0.0, tr1, td1);
        Drop d2 = sliding(b + vec2(0.11, 0.0), 7.6 / size, t * 1.1, uRain * 0.85, 37.0, tr2, td2);
        trail = max(tr1, tr2);
        pick(best, td2);
        pick(best, td1);
        pick(best, d2);
        pick(best, d1);
    }
    vec3 ring = vec3(0.0);
    float SI = 12.0 / size;
    if (uImpact > 0.001) {
        Drop di = impacts(b, SI, t, uImpact, 51.0, ring);
        di.m *= 1.0 - smoothstep(0.02, 0.2, trail);
        pick(best, di);
    }
    if (uDrops > 0.001 && best.m < 0.5) {
        float wipe = 1.0 - smoothstep(0.02, 0.2, trail);
        Drop prev = best;
        best = noDrop();
        Drop s1 = droplets(b, 36.0 / size, 0.37, t, uDrops * 0.75, 1.0);
        Drop s2 = droplets(b + vec2(0.013, 0.021), 58.0 / size, -0.61, t * 1.2, uDrops, 17.0);
        Drop s3 = droplets(b + vec2(0.037, 0.005), 90.0 / size, 1.13, t * 1.4, uDrops, 29.0);
        s1.m *= wipe;
        s2.m *= wipe;
        s3.m *= wipe;
        pick(best, s3);
        pick(best, s2);
        pick(best, s1);
        if (prev.m >= best.m) best = prev;
    }

    // a drop is a small lens: it shows a little, upside-down view of what's behind it
    float k = mix(0.8, 3.2, uRefr);
    vec2 offB = best.e * best.r;
    vec2 dispB = -offB * (1.0 + k * (0.7 + 0.3 * dot(best.e, best.e)));
    vec2 disp = vec2(dispB.x / aspect, dispB.y);

    float clearAmt = clamp(trail * uTrail * 1.3, 0.0, 1.0);
    vec2 rdB = ring.xy * ring.z * (0.35 / SI);
    vec2 ringDisp = vec2(rdB.x / aspect, rdB.y);
    float fall = uFall > 0.001 ? fallingRain(b, t, uFall) * (1.0 - best.m) : 0.0;

    vec3 col;
    if (uMix <= 0.001) {
        col = shade(uTexA, uMidA, uFogA, uRectA, uCoverA, uFxA, s, best, disp, clearAmt, ringDisp, ring.z, fall);
    } else if (uMix >= 0.999) {
        col = shade(uTexB, uMidB, uFogB, uRectB, uCoverB, uFxB, s, best, disp, clearAmt, ringDisp, ring.z, fall);
    } else {
        vec3 a = shade(uTexA, uMidA, uFogA, uRectA, uCoverA, uFxA, s, best, disp, clearAmt, ringDisp, ring.z, fall);
        vec3 c = shade(uTexB, uMidB, uFogB, uRectB, uCoverB, uFxB, s, best, disp, clearAmt, ringDisp, ring.z, fall);
        col = mix(a, c, smoothstep(0.0, 1.0, uMix));
    }
    fragColor = vec4(col, 1.0);
}
