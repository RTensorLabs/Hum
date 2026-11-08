import test from 'node:test';
import assert from 'node:assert/strict';
import { codecOf, describeFormat, rankAudioFormats, playbackStages, hasKnownSpeakerLayout } from '../src/audio-formats.mjs';

const opus = (extra = {}) => ({ itag: 251, mime_type: 'audio/webm; codecs="opus"', bitrate: 160_000, average_bitrate: 135_000,
  audio_quality: 'AUDIO_QUALITY_MEDIUM', audio_sample_rate: 48000, audio_channels: 2, has_audio: true, ...extra });
const aac = (extra = {}) => ({ itag: 140, mime_type: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 131_000, average_bitrate: 129_000,
  audio_quality: 'AUDIO_QUALITY_MEDIUM', audio_sample_rate: 44100, audio_channels: 2, has_audio: true, ...extra });
const best = formats => rankAudioFormats(formats)[0].itag;

test('codecs are read from the mime type', () => {
  assert.equal(codecOf('audio/webm; codecs="opus"'), 'opus');
  assert.equal(codecOf('audio/mp4; codecs="mp4a.40.2"'), 'aac');
  assert.equal(codecOf('audio/mp4; codecs="mp4a.40.5"'), 'he-aac');
  assert.equal(codecOf(undefined), 'unknown');
});

const order = (formats, preference) => rankAudioFormats(formats, preference).map(f => f.itag);

test('the same tier across codecs is a stated preference, never a rescaled bitrate', () => {
  assert.equal(best([aac(), opus()]), 251);
  assert.equal(best([opus(), aac()]), 251);
  // A much higher AAC rate in the same tier is not out-ranked by a codec multiplier either way:
  // codecs of one tier are ordered by the tie-break, and bitrates are only compared within a codec.
  assert.equal(best([aac({ itag: 141, average_bitrate: 256_000 }), aac({ itag: 140 })]), 141);
  assert.equal(best([opus({ itag: 250, average_bitrate: 60_000 }), opus({ itag: 251 })]), 251);
});

test("YouTube's own quality tier outranks codec and raw bitrate", () => {
  const premium = aac({ itag: 141, bitrate: 258_000, average_bitrate: 256_000, audio_quality: 'AUDIO_QUALITY_HIGH' });
  assert.equal(best([opus(), aac(), premium]), 141);
  const lowAac = aac({ itag: 139, audio_quality: 'AUDIO_QUALITY_LOW', average_bitrate: 48_000 });
  assert.deepEqual(order([lowAac, opus(), aac()]), [251, 140, 139]);
});

test('dynamic-range-compressed and volume-boosted renditions are fallbacks, not exclusions', () => {
  const drc = opus({ itag: 774, is_drc: true, average_bitrate: 300_000, audio_quality: 'AUDIO_QUALITY_HIGH' });
  const boosted = aac({ itag: 141, is_vb: true, average_bitrate: 256_000, audio_quality: 'AUDIO_QUALITY_HIGH' });
  assert.equal(best([drc, boosted, aac()]), 140);
  assert.deepEqual(order([drc, boosted, aac()]), [140, 774, 141]);
  assert.equal(best([drc]), 774);
});

test('a higher bitrate never switches language or content', () => {
  const original = opus();
  const dubbed = opus({ itag: 600, is_dubbed: true, language: 'fr', average_bitrate: 300_000, audio_quality: 'AUDIO_QUALITY_HIGH' });
  const autoDubbed = aac({ itag: 601, is_auto_dubbed: true, language: 'de', average_bitrate: 256_000, audio_quality: 'AUDIO_QUALITY_HIGH' });
  assert.equal(best([dubbed, autoDubbed, original]), 251);
  // The original stays ahead of every dub, however they compare on quality.
  assert.equal(order([dubbed, autoDubbed, original])[0], 251);
  // The listener's language wins, and quality only orders what is in that language.
  const frLow = opus({ itag: 602, is_dubbed: true, language: 'fr-FR', average_bitrate: 50_000, audio_quality: 'AUDIO_QUALITY_LOW' });
  assert.deepEqual(order([frLow, dubbed, autoDubbed, original], { language: 'fr' }).slice(0, 2), [600, 602]);
  assert.equal(rankAudioFormats([frLow, autoDubbed, original], { language: 'fr' })[0].itag, 602);
  // A language with no rendition falls back to the original, not to the highest bitrate.
  assert.equal(rankAudioFormats([autoDubbed, original], { language: 'ja' })[0].itag, 251);
});

test('audio description is never selected unless requested, not even as a last fallback', () => {
  const description = opus({ itag: 610, is_descriptive: true, language: 'en', average_bitrate: 300_000, audio_quality: 'AUDIO_QUALITY_HIGH' });
  assert.deepEqual(order([description, aac()]), [140]);
  assert.deepEqual(order([description]), []);
  // Requested: the description first, the normal track behind it.
  assert.deepEqual(order([aac(), description], { audioDescription: true }), [610, 140]);
  // Requested but not offered: the normal rendition, never nothing.
  assert.deepEqual(order([aac()], { audioDescription: true }), [140]);
  // Requested in a language: that language's description first.
  const fr = opus({ itag: 611, is_descriptive: true, language: 'fr' });
  assert.equal(order([description, fr, aac()], { audioDescription: true, language: 'fr' })[0], 611);
});

test('ambisonic and unidentified channel layouts are never selected', () => {
  const ambisonic = opus({ itag: 338, audio_channels: 4, spatial_audio_type: 'AMBISONICS_QUAD', audio_quality: 'AUDIO_QUALITY_HIGH', average_bitrate: 400_000 });
  const foa = opus({ itag: 339, audio_channels: 6, spatial_audio_type: 'AMBISONICS_5_1', audio_quality: 'AUDIO_QUALITY_HIGH' });
  const nonDiegetic = opus({ itag: 340, audio_channels: 6, spatial_audio_type: 'FOA_WITH_NON_DIEGETIC', audio_quality: 'AUDIO_QUALITY_HIGH' });
  // Even a 6-channel ambisonic stream looks like 5.1 by count, so the metadata decides, not the count.
  assert.deepEqual(order([ambisonic, foa, nonDiegetic, aac()]), [140]);
  assert.equal(hasKnownSpeakerLayout(foa), false);
  // The Opus ambisonic itag is recognised even if the metadata is missing.
  assert.deepEqual(order([opus({ itag: 338, audio_channels: 4 }), aac()]), [140]);
  // Counts with no standard layout are excluded; mono, stereo, 5.1 and 7.1 are kept.
  for (const channels of [3, 4, 5, 7, 9, 16]) assert.deepEqual(order([opus({ itag: 700 + channels, audio_channels: channels }), aac()]), [140], `${channels} channels`);
  for (const channels of [1, 2, 6, 8]) assert.equal(order([opus({ itag: 700 + channels, audio_channels: channels, audio_quality: 'AUDIO_QUALITY_HIGH' }), aac()])[0], 700 + channels);
  // Nothing else on offer: nothing is returned rather than an unidentified layout.
  assert.deepEqual(order([ambisonic, foa]), []);
  assert.equal(hasKnownSpeakerLayout(opus({ audio_channels: undefined })), true);
});

test('multichannel is kept, ranked by the same rules, and reports its real channel count', () => {
  const surround = aac({ itag: 380, mime_type: 'audio/mp4; codecs="ec-3"', audio_channels: 6, average_bitrate: 384_000, audio_quality: 'AUDIO_QUALITY_HIGH' });
  assert.equal(best([opus(), surround]), 380);
  assert.equal(best([surround]), 380);
  assert.equal(describeFormat(surround).channels, 6);
  assert.equal(describeFormat(surround).codec, 'ec-3');
  // Not preferred merely for having more channels when its tier is lower.
  assert.equal(best([opus({ audio_quality: 'AUDIO_QUALITY_HIGH', itag: 252 }), { ...surround, audio_quality: 'AUDIO_QUALITY_MEDIUM' }]), 252);
});

test('unknown codecs are the last resort inside a rendition, and ties are deterministic', () => {
  assert.equal(best([{ itag: 9, mime_type: 'audio/webm; codecs="vorbis"', average_bitrate: 500_000, audio_quality: 'AUDIO_QUALITY_HIGH' }, aac()]), 140);
  assert.equal(best([opus({ itag: 252 }), opus({ itag: 251 })]), 251);
  assert.equal(best([opus({ itag: 251, audio_sample_rate: 44100 }), opus({ itag: 253, audio_sample_rate: 48000 })]), 253);
});

test('ranking never mutates its input', () => {
  const input = [aac(), opus()];
  rankAudioFormats(input);
  assert.equal(input[0].itag, 140);
});

test('the selected format is described from YouTube metadata only', () => {
  assert.deepEqual(describeFormat(opus()), { itag: 251, mimeType: 'audio/webm', codec: 'opus', bitrate: 160000, averageBitrate: 135000,
    sampleRate: 48000, channels: 2, audioQuality: 'medium' });
  const bare = describeFormat({ itag: 5, mime_type: 'audio/mp4; codecs="mp4a.40.2"' });
  assert.equal(bare.bitrate, null); assert.equal(bare.sampleRate, null); assert.equal(bare.channels, null); assert.equal(bare.audioQuality, null);
});

test('signed in, account routes come before guests; signed out, the reverse', () => {
  const names = stages => stages.map(s => s.map(([c, v]) => c + (v ? ':proof' : '')));
  assert.deepEqual(names(playbackStages({ signedIn: true })), [['YTMUSIC', 'WEB'], ['YTMUSIC:proof', 'WEB:proof'], ['VISIONOS', 'VISIONOS_MUSIC', 'WEB_EMBEDDED']]);
  assert.deepEqual(names(playbackStages({ signedIn: false })), [['VISIONOS', 'VISIONOS_MUSIC', 'WEB_EMBEDDED'], ['YTMUSIC', 'WEB'], ['YTMUSIC:proof', 'WEB:proof']]);
});

test('a forced retry after a media rejection skips the rejected route and keeps the fallback', () => {
  const skip = (client, verified) => client === 'YTMUSIC' && !verified;
  const forced = playbackStages({ signedIn: true, forceProof: true, skip });
  assert.deepEqual(forced.map(s => s.map(([c, v]) => c + (v ? ':proof' : ''))), [['YTMUSIC:proof', 'WEB:proof'], ['VISIONOS', 'VISIONOS_MUSIC', 'WEB_EMBEDDED']]);
  const plain = playbackStages({ signedIn: true, skip });
  assert.deepEqual(plain[0], [['WEB', false]]);
});
