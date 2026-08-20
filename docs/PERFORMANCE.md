# Performance Targets

These are targets, not claims.

## Preview
- stable 30/60 fps according to selected mode,
- no avoidable UI-thread work,
- first-frame latency measured per device,
- lens-switch interruption measured.

## Photo
Track:
- shutter acknowledgement,
- frame acquisition,
- preview restore,
- processing,
- save,
- peak memory,
- thermals.

## Video
Track:
- record-start latency,
- dropped frames,
- audio/video drift,
- sustained encoder throughput,
- temperature,
- battery drain.

## Memory
Use:
- reusable buffers,
- direct/native buffers,
- file-backed mmap fallback,
- bounded job queues.

Never retain an entire large RAW burst in Java/Kotlin heap unless profiling proves it safe.
