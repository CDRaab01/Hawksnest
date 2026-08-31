/**
 * Prerecorded messages that can be played out of a camera's speaker.
 *
 * Ported 1:1 from `core/logic/QuickReply.kt` — keep the two in lockstep.
 */

/**
 * A prerecorded message that can be played out of a camera's speaker.
 *
 * `file` is a bare filename, not a path: the audio lives on go2rtc's own config volume so one set
 * serves every device and both clients, and so the client never has to hold or upload audio.
 */
export interface QuickReply {
  id: string;
  label: string;
  file: string;
}

/**
 * The messages offered, in order.
 *
 * Deliberately short and few. This is a list you read while somebody is standing at the door, so
 * a scrolling menu of near-identical phrasings would be worse than three obvious ones. Ring ships
 * a comparable set for the same reason.
 *
 * Wording is neutral about whether anyone is home. "We'll be right there" and "nobody can come to
 * the door" both work whether the house is full or empty, which matters for a message a stranger
 * hears.
 *
 * The WAV files must exist on the go2rtc config volume at `/config/replies/<file>` (they ship in
 * `hawksnest-automation`'s replies ConfigMap); a reply whose file is missing fails visibly rather
 * than silently — see `ReplySheet`.
 */
export const QUICK_REPLIES: QuickReply[] = [
  { id: "leave", label: "Please leave it at the door.", file: "leave-at-door.wav" },
  { id: "coming", label: "We'll be right there.", file: "be-right-there.wav" },
  { id: "cant", label: "Nobody can come to the door right now.", file: "cant-come.wav" },
];

/**
 * Percent-encode for a query value.
 *
 * Hand-rolled rather than `encodeURIComponent` so this matches the Kotlin `encode` byte for byte:
 * `encodeURIComponent` leaves `!*'()` raw, and the two clients must produce the same URL for the
 * same reply. Unreserved set is `A-Za-z0-9-_.~`, everything else is `%XX` over UTF-8 bytes — so a
 * space is `%20` and never `+` (go2rtc reads a literal `+` as part of a filename).
 */
function encode(s: string): string {
  let out = "";
  for (const byte of new TextEncoder().encode(s)) {
    const c = String.fromCharCode(byte);
    if (/[A-Za-z0-9\-_.~]/.test(c)) out += c;
    else out += "%" + byte.toString(16).toUpperCase().padStart(2, "0");
  }
  return out;
}

/**
 * Build the go2rtc call that pushes `reply` out of `cameraName`'s speaker.
 *
 * go2rtc's `dst=` form sends a source INTO a stream's audio backchannel, which is how a camera's
 * speaker is reached. That is the whole reason this feature needs no audio code in the client: the
 * obvious approach — swapping a file in for the microphone inside `TalkButton`'s WebRTC session —
 * would mean replacing the outgoing MediaStreamTrack with a decoded file, and would still leave
 * the browser transcoding to whatever the backchannel accepts.
 *
 * **PCMU** because that is what the cameras advertise as their sendonly backchannel codec (their
 * SDP carries `a=rtpmap:0 PCMU/8000` alongside the recvonly AAC). Asking for anything else makes
 * go2rtc transcode at best and fail at worst.
 *
 * Everything is URL-encoded because the source contains `:`, `/` and a `#` fragment that would
 * otherwise be read as the end of the query.
 *
 * Returned as a path (no leading slash), matching the Kotlin. Callers prefix it with the app's
 * same-origin nginx mount — see `replyUrl`.
 */
export function quickReplyPath(cameraName: string, reply: QuickReply): string {
  const src = `ffmpeg:/config/replies/${reply.file}#audio=pcmu`;
  return `go2rtc/api/streams?dst=${encode(cameraName)}&src=${encode(src)}`;
}

/**
 * Absolute, same-origin URL for a reply.
 *
 * go2rtc is reverse-proxied by the app's own nginx at `/go2rtc/` (same mount `go2rtcWsUrl` uses),
 * so the browser never needs to reach go2rtc's host port directly and there is no CORS story.
 */
export function replyUrl(cameraName: string, reply: QuickReply): string {
  return `/${quickReplyPath(cameraName, reply)}`;
}

/**
 * Which cameras can be spoken through at all — the gate for BOTH speaker features, Reply and
 * Talk. (Named for the capability rather than for either button, because it is shared.)
 *
 * Gated on go2rtc serving the camera, because go2rtc is what carries the backchannel — a camera it
 * does not serve has no path to a speaker at all. Reusing the same signal as the live tier
 * (`go2rtc.ts`) rather than inventing a second notion of "can this camera talk", so the two can
 * never disagree.
 *
 * TALK USED TO ASK A DIFFERENT QUESTION and got a wrong answer: it gated on `isRing`, which is
 * about where a camera's RECORDINGS live and says nothing about its speaker. That is the same
 * mistake the live tier made, and it cost the same thing — every Reolink silently excluded, here
 * from a feature they support (E1 Zoom, E1 Pro and the D340W doorbell all advertise a `PCMU/8000`
 * sendonly backchannel).
 *
 * Fails CLOSED: unknown means no button. An action that appears and then does nothing is worse
 * than one that never appeared.
 */
export function canReachSpeaker(go2rtcServesCamera: boolean | null): boolean {
  return go2rtcServesCamera === true;
}
