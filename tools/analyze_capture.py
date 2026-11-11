"""Reads the capture dump and reports what the server receives.

The dump holds one record per chunk. Each record carries the local time of
the first sample, the time the server will play the chunk, the time the
chunk was handed over, and the samples.

The script reports the things that matter for the audio the server plays:

- the spacing of the server timestamps, because the server anchors the
  stream on them,
- the rate the device really delivers, measured against the hand-over
  times, because a wrong rate makes the server resample the whole stream,
- the continuity of the sample stream, because the specification asks the
  source to keep it continuous,
- the shape of the signal, so a dead channel or an offset shows up.

It can also write the samples to a WAV file, so the stream can be heard.
"""

from __future__ import annotations

import argparse
import struct
import sys
import wave

import numpy as np

# The marker that starts the file.
MAGIC = 0x42445353

# The size of the file header.
HEADER_BYTES = 16

# The size of the header of one record.
RECORD_HEADER_BYTES = 39

# The record type of an audio chunk.
RECORD_AUDIO = 1

# The distance in samples that the boundary check looks at.
BOUNDARY_WINDOW = 240

# A boundary step this many times the local spread is a break.
BOUNDARY_LIMIT = 8.0


def read_dump(path: str) -> tuple[dict, list[dict]]:
    """Reads the dump and returns the header and the records."""
    with open(path, "rb") as handle:
        data = handle.read()
    if len(data) < HEADER_BYTES:
        raise ValueError("the file is too short to hold a header")
    magic, version, channels, sample_rate = struct.unpack_from("<IIII", data, 0)
    if magic != MAGIC:
        raise ValueError("the file does not start with the marker")
    header = {
        "version": version,
        "channels": channels,
        "sample_rate": sample_rate,
    }
    records: list[dict] = []
    offset = HEADER_BYTES
    while offset + RECORD_HEADER_BYTES <= len(data):
        length = struct.unpack_from("<I", data, offset)[0]
        kind = data[offset + 4]
        if length < RECORD_HEADER_BYTES or offset + length > len(data):
            break
        if kind == RECORD_AUDIO:
            capture_ts, server_ts, send_ts = struct.unpack_from("<qqq", data, offset + 5)
            frames = struct.unpack_from("<i", data, offset + 29)[0]
            records.append(
                {
                    "capture_ts": capture_ts,
                    "server_ts": server_ts,
                    "send_ts": send_ts,
                    "frames": frames,
                    "samples": data[offset + RECORD_HEADER_BYTES : offset + length],
                }
            )
        offset += length
    return header, records


def spacing_report(name: str, values: np.ndarray, expected: float) -> None:
    """Prints how the steps between the values are spread."""
    if values.size < 2:
        print(f"{name}: there are too few values")
        return
    steps = np.diff(values)
    exact = int(np.count_nonzero(steps == expected))
    print(f"{name}: {steps.size} steps")
    print(f"  the smallest step is {steps.min()} and the largest is {steps.max()} microseconds")
    print(f"  the mean step is {steps.mean():.1f} and the spread is {steps.std():.1f} microseconds")
    print(f"  {exact} of {steps.size} steps are exactly {expected:.0f} microseconds")
    values_, counts = np.unique(steps, return_counts=True)
    order = np.argsort(-counts)[:8]
    print("  the common steps are " + ", ".join(
        f"{int(values_[i])} x{int(counts[i])}" for i in order
    ))
    worst = np.argsort(-np.abs(steps - expected))[:5]
    for index in worst:
        if steps[index] != expected:
            print(f"  the step at chunk {index} is {steps[index]} microseconds")


def rate_report(records: list[dict], capture: np.ndarray, sample_rate: int) -> None:
    """Prints the rate the device really delivers."""
    frames = np.array([record["frames"] for record in records], dtype=np.float64)
    send = np.array([record["send_ts"] for record in records], dtype=np.float64)
    if send.size < 3:
        print("the real rate: there are too few records")
        return
    span = (send[-1] - send[0]) / 1_000_000.0
    if span <= 0:
        print("the real rate: the run has no length")
        return
    real = frames.sum() / span
    print("the real rate")
    print(f"  the device delivers about {real:.0f} frames a second, and the client announces {sample_rate}")
    print(f"  the difference is about {(real / sample_rate - 1.0) * 100.0:+.3f} percent")
    print("  the measure is a little high, because the chunks are handed over in bursts")
    steps = np.diff(capture) / 1_000_000.0
    chunk_rates = frames[:-1] / np.where(steps > 0, steps, np.nan)
    chunk_rates = chunk_rates[np.isfinite(chunk_rates)]
    if chunk_rates.size:
        print(f"  the rate the server reads from the timestamps is {np.median(chunk_rates):.1f} frames a second")


def continuity_report(records: list[dict], channels: int, sample_rate: int) -> None:
    """Prints whether the sample stream is continuous."""
    frames = np.array([record["frames"] for record in records], dtype=np.float64)
    server = np.array([record["server_ts"] for record in records], dtype=np.float64)
    if server.size < 2:
        print("the continuity: there are too few records")
        return
    span = (server[-1] - server[0]) / 1_000_000.0
    expected = span * sample_rate
    actual = frames.sum()
    print("the continuity")
    print(f"  the timestamps cover {span:.3f} seconds, which holds {expected:.0f} frames")
    print(f"  the chunks hold {actual:.0f} frames")
    print(f"  the difference is {actual - expected:+.0f} frames")
    gaps = np.diff(server) - frames[:-1] * 1_000_000.0 / sample_rate
    print(f"  the largest gap between the chunks is {gaps.max():.0f} microseconds")
    print(f"  the smallest gap between the chunks is {gaps.min():.0f} microseconds")


def boundary_report(records: list[dict], channels: int) -> None:
    """Prints the places where the signal breaks at a chunk edge."""
    pieces = []
    for record in records:
        samples = np.frombuffer(record["samples"], dtype="<i2")
        if channels > 1:
            samples = samples.reshape(-1, channels)[:, 0]
        pieces.append(samples.astype(np.float64))
    if not pieces:
        print("the boundaries: there are no samples")
        return
    signal = np.concatenate(pieces)
    if signal.size < 4 * BOUNDARY_WINDOW:
        print("the boundaries: there are too few samples")
        return
    steps = np.diff(signal)
    edges = np.cumsum([record["frames"] for record in records])[:-1]
    edges = edges[(edges > BOUNDARY_WINDOW) & (edges < signal.size - BOUNDARY_WINDOW)]
    breaks = 0
    worst = 0.0
    for edge in edges:
        index = int(edge) - 1
        around = np.concatenate(
            [steps[index - BOUNDARY_WINDOW : index], steps[index + 1 : index + 1 + BOUNDARY_WINDOW]]
        )
        spread = around.std()
        if spread <= 0:
            continue
        score = abs(steps[index] - around.mean()) / spread
        if score > worst:
            worst = score
        if score > BOUNDARY_LIMIT:
            breaks += 1
    print("the boundaries")
    print(f"  {len(edges)} chunk edges were checked")
    print(f"  {breaks} of them break the signal, and the worst score is {worst:.1f}")
    if breaks:
        print("  a break means a sample was dropped or repeated at that edge")


def signal_report(records: list[dict], channels: int, sample_rate: int) -> None:
    """Prints the shape of the signal."""
    pieces = []
    for record in records:
        samples = np.frombuffer(record["samples"], dtype="<i2")
        pieces.append(samples.reshape(-1, channels).astype(np.float64))
    if not pieces:
        print("the signal: there are no samples")
        return
    signal = np.concatenate(pieces, axis=0)
    print("the signal")
    for channel in range(channels):
        column = signal[:, channel]
        peak = int(np.max(np.abs(column)))
        offset = float(column.mean())
        print(f"  channel {channel}: the loudest sample is {peak} and the offset is {offset:.1f}")
    if channels > 1:
        left = signal[:, 0]
        right = signal[:, 1]
        spread = np.std(left) * np.std(right)
        if spread > 0:
            print(f"  the two channels correlate at {np.corrcoef(left, right)[0, 1]:.4f}")
    middle = signal[len(signal) // 4 : len(signal) // 4 + 1 << 16, 0]
    if middle.size > 1024:
        window = np.hanning(middle.size)
        spectrum = np.abs(np.fft.rfft(middle * window))
        frequencies = np.fft.rfftfreq(middle.size, 1.0 / sample_rate)
        peaks = np.argsort(-spectrum)[:10]
        peaks = sorted(set(round(float(frequencies[p])) for p in peaks if spectrum[p] > spectrum.max() * 0.05))
        print(f"  the loudest frequencies are {peaks[:8]}")
        print(f"  the energy above 12 kHz is {100.0 * np.sum(spectrum[frequencies > 12000]) / max(np.sum(spectrum), 1e-9):.2f} percent")


def write_wav(path: str, records: list[dict], channels: int, sample_rate: int) -> None:
    """Writes the samples to a WAV file, so the stream can be heard."""
    pieces = [record["samples"] for record in records]
    if not pieces:
        raise ValueError("there are no samples to write")
    data = b"".join(pieces)
    with wave.open(path, "wb") as handle:
        handle.setnchannels(channels)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(data)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump", help="the capture dump the app wrote")
    parser.add_argument("--wav", default="", help="write the samples to this WAV file")
    args = parser.parse_args()

    header, records = read_dump(args.dump)
    channels = header["channels"]
    sample_rate = header["sample_rate"]
    print(f"the dump holds {len(records)} chunks")
    print(f"the format is {channels} channels at {sample_rate} hertz")
    if not records:
        return

    server = np.array([record["server_ts"] for record in records], dtype=np.float64)
    capture = np.array([record["capture_ts"] for record in records], dtype=np.float64)
    send = np.array([record["send_ts"] for record in records], dtype=np.float64)

    # A chunk holds twenty milliseconds of audio, so the step between the
    # timestamps of two chunks is twenty milliseconds.
    expected = 20_000
    spacing_report("the server timestamps", server, expected)
    spacing_report("the capture timestamps", capture, expected)
    spacing_report("the hand-over times", send, expected)

    offset = server - capture
    print("the offset between the capture and the server clock")
    print(f"  the smallest is {offset.min():.0f} and the largest is {offset.max():.0f} microseconds")
    print(f"  it moves by {offset[-1] - offset[0]:+.0f} microseconds over the run")

    rate_report(records, capture, sample_rate)
    continuity_report(records, channels, sample_rate)
    boundary_report(records, channels)
    signal_report(records, channels, sample_rate)

    if args.wav:
        write_wav(args.wav, records, channels, sample_rate)
        print(f"the samples were written to {args.wav}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001
        import traceback

        traceback.print_exc()
        sys.exit(f"the dump could not be read: {error}")
