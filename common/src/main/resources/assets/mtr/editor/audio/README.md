# Ogg Vorbis encoder

`OggVorbisEncoder.js` is the uncompressed, self-contained browser encoder from
https://github.com/higuma/ogg-vorbis-encoder-js (lib/OggVorbisEncoder.js).
It is loaded only inside `convert-worker.js`, without a CDN or WebAssembly.
Vendored SHA-256: `5a9f749ab0f84da2292bd68b0e906422378428aea2e298fd116e8a1696da179b`.
The wrapper is copyright 2015 Yuji Miyane, licensed under MIT (LICENSE-MIT.txt).
The embedded libogg/libvorbis code uses Xiph's BSD license (LICENSE-Xiph.txt).

Our worker encodes mono speech at 24 kHz, Vorbis quality 0.3. The main page
decodes WAV or MP3 using Web Audio, checks duration before decoding, resamples locally,
and checks the encoded size against the server limit before upload.
