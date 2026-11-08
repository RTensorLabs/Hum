// Choosing among YouTube's audio-only formats, and the order playback routes are tried in.
// Pure functions, so the rules can be tested without a YouTube session.
//
// Two separate steps:
//   1. RENDITION: which version of the recording is wanted (the original audio, or the
//      language / audio description the listener asked for; standard rather than
//      dynamic-range-compressed or volume-boosted).
//   2. QUALITY: the best available encoding of that rendition.
// A higher bitrate never changes the rendition.

const TIERS = { AUDIO_QUALITY_ULTRALOW: 0, AUDIO_QUALITY_LOW: 1, AUDIO_QUALITY_MEDIUM: 2, AUDIO_QUALITY_HIGH: 3 };
// Codecs this player decodes. Others are kept as a last resort rather than dropped.
const DECODABLE = new Set(['opus', 'aac', 'he-aac', 'ac-3', 'ec-3']);
// Used ONLY to break a tie between codecs that YouTube labels with the same quality tier and
// that this code cannot otherwise tell apart. It is a stated preference, not a measurement:
// no codec is assumed to sound better in general, and no bitrate is rescaled across codecs.
const CODEC_TIEBREAK = ['opus', 'aac', 'ec-3', 'ac-3', 'he-aac'];

/** 'opus', 'aac' (AAC-LC), 'he-aac', 'ac-3', 'ec-3', or the raw codec string. */
export function codecOf(mimeType) {
  const codecs = /codecs="?([^";]+)/i.exec(String(mimeType || ''))?.[1]?.toLowerCase() || '';
  if (codecs.startsWith('opus')) return 'opus';
  if (/^mp4a\.40\.(5|29|42)$/.test(codecs)) return 'he-aac';
  if (codecs.startsWith('mp4a')) return 'aac';
  return codecs || 'unknown';
}

const positive = value => { const n = Number(value); return Number.isFinite(n) && n > 0 ? Math.round(n) : null; };
const language = value => String(value || '').toLowerCase().split(/[-_]/)[0] || null;

/** What YouTube itself says about a format; null where it says nothing. */
export function describeFormat(format) {
  return {
    itag: Number(format.itag),
    mimeType: String(format.mime_type || '').split(';')[0] || null,
    codec: codecOf(format.mime_type),
    bitrate: positive(format.bitrate),
    averageBitrate: positive(format.average_bitrate),
    sampleRate: positive(format.audio_sample_rate),
    channels: positive(format.audio_channels),
    audioQuality: typeof format.audio_quality === 'string' ? format.audio_quality.replace(/^AUDIO_QUALITY_/, '').toLowerCase() : null,
  };
}

/** Which version of the recording a format carries. */
export function renditionOf(format) {
  const kind = format.is_descriptive ? 'description' : format.is_dubbed || format.is_auto_dubbed ? 'dubbed' : format.is_secondary ? 'secondary' : 'original';
  return {
    kind,
    language: language(format.language || format.audio_track?.language_code),
    // Dynamic-range-compressed and volume-boosted renditions are the same recording processed.
    standard: !format.is_drc && !format.is_vb,
  };
}

/** Quality order within ONE rendition, best first (negative = a is better). */
function byQuality(a, b) {
  const da = describeFormat(a), db = describeFormat(b);
  // 1. YouTube's own tier, when it states one for both.
  const ta = TIERS[a.audio_quality], tb = TIERS[b.audio_quality];
  if (ta !== undefined && tb !== undefined && ta !== tb) return tb - ta;
  // 2. Same codec: the higher average (else peak) bitrate. Bitrates are never compared across codecs.
  const ra = da.averageBitrate || da.bitrate || 0, rb = db.averageBitrate || db.bitrate || 0;
  if (da.codec === db.codec && ra !== rb) return rb - ra;
  // 3. Different codecs of the same tier: a stated preference, then the rest.
  if (da.codec !== db.codec) {
    const pa = DECODABLE.has(da.codec) ? CODEC_TIEBREAK.indexOf(da.codec) : 99, pb = DECODABLE.has(db.codec) ? CODEC_TIEBREAK.indexOf(db.codec) : 99;
    if (pa !== pb) return pa - pb;
    return rb - ra;
  }
  return (db.sampleRate || 0) - (da.sampleRate || 0) || da.itag - db.itag;
}

// Channel counts whose speaker positions are standard: mono, stereo, 5.1 and 7.1. Anything else
// (3, 4, 5, 7, 9+ channels) cannot be mapped to speakers from the count alone.
const CONVENTIONAL_CHANNELS = new Set([1, 2, 6, 8]);

/**
 * Can this format's channels be assigned to speakers? Ambisonic audio (YouTube marks it with
 * `spatial_audio_type`; itag 338 is its Opus form) carries sound-field components, not speaker
 * feeds, and a conventional surround downmix would play them as if they were L/R/C/LFE/Ls/Rs.
 * An unusual channel count has no standard layout either. A missing count is treated as the
 * ordinary stereo case; the player re-checks the decoded channels.
 */
export function hasKnownSpeakerLayout(format) {
  if (format.spatial_audio_type || Number(format.itag) === 338) return false;
  const channels = positive(format.audio_channels);
  return channels === null || CONVENTIONAL_CHANNELS.has(channels);
}

/**
 * Candidates in the order to try them, best first.
 * - `language`: the listener's language (e.g. 'fr'). Unset means the original audio.
 * - `audioDescription`: true ONLY when the listener asked for it (accessibility). Audio
 *   description is commentary over the recording, so it is never a fallback for the normal
 *   track: when it is not requested it is not returned at all, and when it is requested but
 *   absent the normal rendition is returned instead.
 * Rendition groups come first, in this order, and quality only orders formats inside a group:
 *   wanted rendition (standard, then DRC / volume-boost) → original (standard, then processed)
 *   → any other non-description rendition (other dubs).
 * Multichannel formats with a standard layout are kept and rank by the same quality rules;
 * ambisonic and unidentified layouts are never returned.
 */
export function rankAudioFormats(formats, { language: wanted = null, audioDescription = false } = {}) {
  const lang = language(wanted);
  const info = formats.filter(hasKnownSpeakerLayout)
    .map(format => ({ format, ...renditionOf(format), supported: DECODABLE.has(codecOf(format.mime_type)) }));
  const groups = [];
  if (audioDescription) groups.push(info.filter(f => f.kind === 'description' && (!lang || f.language === lang)), info.filter(f => f.kind === 'description'));
  if (lang) groups.push(info.filter(f => f.kind !== 'description' && f.language === lang && f.kind !== 'secondary'));
  groups.push(info.filter(f => f.kind === 'original'), info.filter(f => f.kind !== 'description'));
  const out = [], seen = new Set();
  for (const group of groups) {
    // Within a rendition: standard first, processed variants as fallbacks; a decodable codec first.
    for (const standard of [true, false]) for (const supported of [true, false]) {
      const part = group.filter(f => f.standard === standard && f.supported === supported && !seen.has(f.format));
      part.sort((a, b) => byQuality(a.format, b.format));
      for (const f of part) { seen.add(f.format); out.push(f.format); }
    }
  }
  return out;
}

export const GUEST_CLIENTS = ['VISIONOS', 'VISIONOS_MUSIC', 'WEB_EMBEDDED'];
export const ACCOUNT_CLIENTS = ['YTMUSIC', 'WEB'];

/**
 * The stages of playback routes to try, in order. Signed in, the account's routes go first
 * (they are the ones that can expose more than the guest routes do) and guests are the
 * fallback; signed out, the proof-free guest routes go first. `skip(client, verified)`
 * drops routes already rejected.
 */
export function playbackStages({ signedIn, forceProof = false, skip = () => false }) {
  const pick = (clients, verified) => clients.filter(client => !skip(client, verified)).map(client => [client, verified]);
  const guests = pick(GUEST_CLIENTS, false), plain = pick(ACCOUNT_CLIENTS, false), proof = pick(ACCOUNT_CLIENTS, true);
  if (signedIn) return forceProof ? [proof, guests] : [plain, proof, guests];
  return forceProof ? [guests, proof] : [guests, plain, proof];
}
