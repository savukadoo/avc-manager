#!/usr/bin/env python3
"""Render an original instrumental EQ test loop to stereo PCM."""
import math
import random
import struct
import sys
import wave

rate = 44100
beat = 60 / 112
count = round(32 * beat * rate)
left = [0.0] * count
right = [0.0] * count
rng = random.Random(32107)
tau = 2 * math.pi


def add(start, duration, instrument, pan=0.0):
    index = round(start * rate)
    gain_l = math.sqrt((1 - pan) / 2)
    gain_r = math.sqrt((1 + pan) / 2)
    for frame in range(min(round(duration * rate), count - index)):
        sample = instrument(frame / rate)
        left[index + frame] += sample * gain_l
        right[index + frame] += sample * gain_r


def pitch(note):
    return 440 * 2 ** ((note - 69) / 12)


def bass(note):
    f = pitch(note)
    return lambda t: min(t / 0.012, 1) * math.exp(-7 * t) * (
        0.34 * math.sin(tau * f * t) + 0.10 * math.sin(tau * f * 2 * t)
        + 0.05 * math.sin(tau * f * 3 * t))


def keys(note, amplitude, decay):
    f = pitch(note)
    return lambda t: amplitude * min(t / 0.009, 1) * math.exp(-decay * t) * (
        math.sin(tau * f * t) + 0.40 * math.sin(tau * f * 2 * t)
        + 0.18 * math.sin(tau * f * 3 * t) + 0.07 * math.sin(tau * f * 4 * t))


def kick(t):
    return 0.50 * min(t / 0.003, 1) * math.exp(-15 * t) * math.sin(
        tau * (49 * t + 60 * (1 - math.exp(-35 * t)) / 35))


def snare(t):
    return min(t / 0.002, 1) * math.exp(-23 * t) * (
        0.21 * rng.uniform(-1, 1) + 0.09 * math.sin(tau * 182 * t))


def hat(t):
    return min(t / 0.001, 1) * math.exp(-57 * t) * (
        0.09 * rng.uniform(-1, 1) + 0.045 * math.sin(tau * 8259 * t))


roots = [45, 41, 48, 43, 45, 41, 48, 43]
chords = [(60, 64, 67, 71), (60, 65, 69, 72), (59, 64, 67, 74), (59, 62, 65, 69)]
melody = [(76, 79, 81), (77, 76, 72), (74, 76, 79), (74, 72, 67)]
for bar, root in enumerate(roots):
    start = 4 * bar
    for i in range(4):
        add((start + i) * beat, 0.28, kick)
        if i % 2:
            add((start + i) * beat, 0.2, snare, 0.12)
        add((start + i + 0.5) * beat, 0.11, hat, 0.40 if i % 2 else -0.35)
    for step, shift in [(0.25, 0), (0.75, 0), (1.5, 12), (2.25, 0), (3, 7), (3.5, 12)]:
        add((start + step) * beat, 0.25, bass(root + shift))
    for step in [0.5, 1.75, 2.5, 3.75]:
        for index, note in enumerate(chords[bar % 4]):
            add((start + step) * beat, 0.32, keys(note, 0.058, 9), -0.48 + index * 0.32)
    for step, note in zip([0.75, 2, 3.25], melody[bar % 4]):
        add((start + step) * beat, 0.34, keys(note, 0.12, 7), 0.16)

peak = max(max(map(abs, left)), max(map(abs, right)))
audio = bytearray()
for frame, (l, r) in enumerate(zip(left, right)):
    fade = min(1, frame / (0.012 * rate), (count - frame - 1) / (0.035 * rate))
    gain = 0.66 * 32767 * fade / peak
    audio.extend(struct.pack("<hh", round(l * gain), round(r * gain)))
with wave.open(sys.argv[1], "wb") as stream:
    stream.setparams((2, 2, rate, count, "NONE", "not compressed"))
    stream.writeframes(audio)
