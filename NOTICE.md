# Notices and Open-Source Software Attributions

This repository, **Meshtastic-Android Neural Audio & Live PTT**, is an open-source research fork derived from the official Meshtastic-Android application. This project incorporates, modifies, and builds upon several open-source libraries, neural architectures, and speech codecs. We gratefully acknowledge the authors, creators, and contributors of the following works:

---

### 1. Research Fork Modifications
- **Project:** Meshtastic-Android Neural Audio & Tactical Live PTT Research Fork
- **Fork Author & Maintainer:** Mohammask ([GitHub Profile](https://github.com/Mohammask))
- **Copyright:** © 2026 Mohammask.
- **License:** GNU General Public License v3.0 ([GPLv3](LICENSE)).
- **Key Modifications:** 
  - On-device 24kHz WavTokenizer neural speech vocoder integration with ONNX Runtime Mobile.
  - Asynchronous 3-byte micro-framing protocol (`ChunkingEngine`).
  - Selective-Repeat ARQ and graceful Packet Loss Concealment (`ReassemblyBuffer`).
  - Ephemeral RAM-only simplex Live PTT engine and micro-packet floor arbitration (`LivePttSessionManager`).
  - Adaptive Time-on-Air (ToA) LoRa radio dispatch pacing (`PayloadAnalyzer`).

---

### 2. Meshtastic Android Client (Upstream)
- **Upstream Project:** [Meshtastic-Android](https://github.com/meshtastic/Meshtastic-Android)
- **Copyright:** © 2020–2026 Meshtastic LLC and individual contributors.
- **License:** GNU General Public License v3.0 ([GPLv3](LICENSE)).
- **Description:** An open-source, off-grid decentralized mesh communications platform over LoRa.

---

### 3. WavTokenizer (Neural Speech Codec)
- **Authors:** Shengpeng Ji, Ziyue Jiang, Xi Chen, Rongjie Huang, Jialong Zhai, Qian Yang, Zehan Wang, Zhou Zhao (Zhejiang University & Ant Group).
- **Paper:** *"WavTokenizer: an Efficient Acoustic Discrete Codec Tokenizer with Extreme Low Bitrate"* ([arXiv:2408.16532](https://arxiv.org/abs/2408.16532)).
- **Repository:** [jishengpeng/WavTokenizer](https://github.com/jishengpeng/WavTokenizer)
- **License:** Apache License 2.0 / MIT.
- **Usage:** Neural encoder and quantized discrete token vocoder weights (`encoder_int8.onnx`, `model_int8.onnx`) powering 24kHz wideband voice notes at 75 tokens/sec (~900 bps).

---

### 4. Codec 2 (Ultra-Narrowband Speech Vocoder)
- **Author & Maintainer:** David Rowe and Codec 2 Contributors.
- **Repository:** [drowe67/codec2](https://github.com/drowe67/codec2)
- **License:** GNU Lesser General Public License v2.1 ([LGPLv2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)).
- **Usage:** Low-bitrate fallback speech synthesis and sinusoidal LPC vocoding (700C / 1400 bps modes) via bundled native libraries (`libcodec2.so`).

---

### 5. ONNX Runtime Mobile
- **Copyright:** © Microsoft Corporation.
- **Repository:** [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime)
- **License:** MIT License.
- **Usage:** Cross-platform hardware-accelerated inferencing engine executing quantized int8 WavTokenizer models entirely on-device on Android without external network dependencies.

---

### 6. Android Open Source Project (AOSP) & Jetpack
- **Copyright:** © Google LLC and The Android Open Source Project.
- **License:** Apache License 2.0 ([Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)).
- **Usage:** Android Jetpack Compose, Kotlin Multiplatform (KMP), Coroutines, Room persistence, and Core audio hardware HAL integrations.
