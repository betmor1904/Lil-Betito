"""Generates the extra game sounds (tick, clink, rumble, siren, fanfare, squish) into app/src/main/res/raw.
Run: python3 tools/make_sfx_extra.py   (needs numpy)"""
import numpy as np, wave, os
SR = 22050
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")
rng = np.random.default_rng(11)

def save(name, x, peak=0.9):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())

def t(dur): return np.arange(int(dur * SR)) / SR
def lowpass(x, k): return np.convolve(x, np.ones(k) / k, mode="same")
def fade(x, a=0.005, b=0.03):
    n = len(x); y = x.copy()
    na, nb = int(a * SR), int(b * SR)
    y[:na] *= np.linspace(0, 1, na); y[-nb:] *= np.linspace(1, 0, nb)
    return y

# soft tick for plain walls and the floor
n = t(0.05); save("tick", fade(np.sin(2 * np.pi * 1900 * n) * np.exp(-n * 90)))

# chip clink for obstacle hits
n = t(0.22)
clink = (np.sin(2 * np.pi * 2600 * n) * np.exp(-n * 22) + 0.6 * np.sin(2 * np.pi * 3900 * n) * np.exp(-n * 30)
         + 0.3 * np.sin(2 * np.pi * 5200 * n) * np.exp(-n * 40))
save("clink", fade(clink))

# rising rumble for the stuck state
n = t(1.3); f = 40 + 55 * (n / 1.3) ** 1.5
ph = np.cumsum(f) / SR * 2 * np.pi
x = np.sin(ph) + 0.5 * np.sin(ph * 2) + 0.9 * lowpass(rng.standard_normal(len(n)), 60)
save("rumble", fade(x * (0.25 + 0.75 * (n / 1.3) ** 0.8), 0.05, 0.1))

# jackpot siren for huge combos
n = t(1.0); f = 900 + 350 * np.sin(2 * np.pi * 3.5 * n)
ph = np.cumsum(f) / SR * 2 * np.pi
x = 0.5 * np.sign(np.sin(ph)) + 0.5 * np.sin(ph)
save("siren", fade(x, 0.01, 0.15))

# escape fanfare
def note(freq, dur):
    n = t(dur)
    x = np.sin(2 * np.pi * freq * n) + 0.4 * np.sin(4 * np.pi * freq * n) + 0.2 * np.sin(6 * np.pi * freq * n)
    return fade(x * np.exp(-n * 3), 0.005, 0.04)
fan = np.concatenate([note(523.25, 0.13), note(659.25, 0.13), note(783.99, 0.13), note(1046.5, 0.6)])
save("fanfare", fan)

# squish for the death
n = t(0.45); f = 320 * np.exp(-n * 6) + 50
ph = np.cumsum(f) / SR * 2 * np.pi
x = np.sin(ph) * np.exp(-n * 5) + 0.8 * lowpass(rng.standard_normal(len(n)), 12) * np.exp(-n * 12)
save("squish", fade(x))
print("done")
