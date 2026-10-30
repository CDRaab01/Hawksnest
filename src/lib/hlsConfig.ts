/**
 * hls.js buffer bounds. Its defaults are tuned for a single long watch — 30 s ahead that may grow
 * to 600 s, a 60 MB ceiling, and an unbounded back buffer. Measured 2026-10-30 on a Frigate VOD
 * page: after one seek it pulled ~1.4 MB segments back-to-back every ~35 ms, footage a scrubbing
 * user mostly skips past, and on a phone over the tailnet that download competes with the very
 * segment the next seek needs. Ahead is held to 30 s (60 s ceiling — one Frigate segment is ~60 s,
 * so it must fit), and the minute behind the playhead is kept so a short backwards scrub replays
 * from memory instead of refetching.
 */
export const HLS_BUFFER_CONFIG = {
  maxBufferLength: 30,
  maxMaxBufferLength: 60,
  maxBufferSize: 30 * 1000 * 1000,
  backBufferLength: 60,
} as const;
