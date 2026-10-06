"""Generates the turtle voice + bonk sounds into app/src/main/res/raw.
Run: python3 tools/make_sfx.py   (needs numpy + scipy)
Cartoon voice = glottal pulse train pushed through moving formant filters.
Want a real voice instead? Just replace wee.wav / wee2.wav / letsgo.wav
with your own recordings (same names, 16-bit mono WAV)."""
import numpy as np, wave, os
SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
rng = np.random.default_rng(7)

def save(name, x, peak=0.9):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())

def interp(points, n):
    """points = [(t01, value), ...] -> n samples"""
    t = np.array([p[0] for p in points]); v = np.array([p[1] for p in points])
    return np.interp(np.linspace(0, 1, n), t, v)

def source(f0):
    """glottal-ish buzz from a pitch contour (Hz per sample)"""
    ph = np.cumsum(f0 / SR)
    saw = 2 * (ph % 1) - 1
    return saw - 0.5 * np.roll(saw, 1) + 0.03 * rng.standard_normal(len(f0))

def formant_filter(x, F, BW, gains):
    """time-varying resonators. F/BW: list of per-sample arrays"""
    out = np.zeros(len(x))
    for k in range(len(F)):
        y1 = y2 = 0.0
        f, bw = F[k], BW[k]
        r = np.exp(-np.pi * bw / SR)
        c1 = 2 * r * np.cos(2 * np.pi * f / SR)
        c2 = -r * r
        g = 1 - c1 / (1 - c2) * 0  # unity-ish
        y = np.zeros(len(x))
        for i in range(len(x)):
            v = (1 - r[i]) * x[i] + c1[i] * y1 + c2[i] * y2
            y[i] = v; y2 = y1; y1 = v
        out += gains[k] * y
    return out

def env_ad(n, a=0.01, d=0.05):
    e = np.ones(n); na = min(int(a * SR), n // 2); nd = min(int(d * SR), n // 2)
    e[:na] = np.linspace(0, 1, na); e[-nd:] = np.linspace(1, 0, nd)
    return e

def voiced(dur, f0pts, fpts, bw=(90, 110, 160), vib=(0, 0), gains=(1.0, 0.7, 0.35)):
    n = int(dur * SR)
    f0 = interp(f0pts, n)
    if vib[0]:
        f0 = f0 * (1 + vib[1] * np.sin(2 * np.pi * vib[0] * np.arange(n) / SR))
    F = [interp(p, n) for p in fpts]
    BW = [np.full(n, b, float) for b in bw]
    return formant_filter(source(f0), F, BW, gains) * env_ad(n)

def noise_burst(dur, lo, hi, amp=1.0):
    from scipy.signal import butter, lfilter
    n = int(dur * SR)
    b, a = butter(2, [lo / (SR / 2), min(hi, SR / 2 - 200) / (SR / 2)], btype="band")
    x = lfilter(b, a, rng.standard_normal(n)) * amp
    return x * env_ad(n, 0.004, min(0.03, dur / 2))

def gap(dur): return np.zeros(int(dur * SR))

# kid-ish scale factor on formants
K = 1.18

# ---------- "WEEEEE!" ----------
def wee(top=620, dur=1.15):
    w = voiced(0.07, [(0, 300), (1, 330)], [[(0, 320 * K), (1, 300 * K)], [(0, 760 * K), (1, 2300 * K)], [(0, 2300), (1, 3100)]], gains=(1, .6, .3))
    ee = voiced(dur,
        [(0, 330), (.25, top), (.7, top * 0.92), (1, top * 0.55)],
        [[(0, 290 * K), (1, 280 * K)], [(0, 2300 * K), (1, 2250 * K)], [(0, 3100), (1, 3050)]],
        vib=(7.5, 0.035), gains=(1, .9, .5))
    return np.concatenate([w, ee])

save("wee", wee())
save("wee2", wee(top=760, dur=0.9))

# ---------- "LET'S GOOOO!" ----------
def letsgo():
    l = voiced(0.07, [(0, 260), (1, 280)], [[(0, 360 * K), (1, 560 * K)], [(0, 1300 * K), (1, 1850 * K)], [(0, 2800), (1, 2500)]], gains=(1, .6, .3))
    e = voiced(0.13, [(0, 290), (1, 340)], [[(0, 560 * K), (1, 580 * K)], [(0, 1850 * K), (1, 1900 * K)], [(0, 2500), (1, 2550)]], gains=(1, .8, .4))
    t = np.concatenate([gap(0.03), noise_burst(0.015, 3000, 8000, 0.6)])
    s = noise_burst(0.14, 4500, 9500, 0.45)
    g_closure = voiced(0.04, [(0, 280), (1, 300)], [[(0, 300), (1, 300)], [(0, 1200), (1, 1200)], [(0, 2300), (1, 2300)]], gains=(.5, .2, .1))
    g_burst = noise_burst(0.012, 800, 3500, 0.5)
    o = voiced(0.65,
        [(0, 340), (.15, 520), (.6, 700), (1, 520)],
        [[(0, 480 * K), (.3, 520 * K), (1, 380 * K)], [(0, 1000 * K), (.3, 950 * K), (1, 760 * K)], [(0, 2500), (1, 2400)]],
        vib=(7, 0.03), gains=(1, .8, .3))
    return np.concatenate([l, e, t, s, g_closure, g_burst, o])

save("letsgo", letsgo())

# ---------- BONK BONK ----------
def bonk(f=430, dur=0.17):
    n = int(dur * SR); t = np.arange(n) / SR
    pitch = f * (1 + 0.55 * np.exp(-t * 38))           # quick downward pitch dive
    body = np.sin(2 * np.pi * np.cumsum(pitch) / SR) * np.exp(-t * 24)
    wood = 0.35 * np.sin(2 * np.pi * np.cumsum(pitch * 2.4) / SR) * np.exp(-t * 55)
    click = noise_burst(0.012, 1500, 6000, 0.8)
    x = body + wood
    x[:len(click)] += click
    return x

b1 = bonk(450)
b2 = bonk(370)
dbl = np.zeros(int(0.42 * SR))
dbl[:len(b1)] += b1
off = int(0.15 * SR)
dbl[off:off + len(b2)] += 0.9 * b2
save("bonk", dbl)
print("done")
