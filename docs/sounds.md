# Call sounds

Element Call web's own files, copied unchanged so a call sounds the same on every client in it (feature-hq spec 024, R3).
Do not re-encode them: the hashes are how a reviewer checks they still match web and iOS, and `CallSoundEffectAssetsTest`
fails if they drift.

| File in `call/impl/src/main/res/raw/` | Source | SHA-256 |
| :--- | :--- | :--- |
| `element_call_join_call.mp3` | `element-call` `src/sound/join_call.mp3` at ea6f2952a | `2a5e12c8470f6602249577e6c783231d70e80784504bae419a299330bc5a53f8` |
| `element_call_left_call.mp3` | `element-call` `src/sound/left_call.mp3` at ea6f2952a | `5f4c529262388c3cae87df5957e9607ac438cba2be08d1b84484adf73c137971` |

Created by Element (`element-call` `src/sound/LICENCE.md`). The mp3s rather than web's `.ogg` twins because iOS cannot
decode Ogg Vorbis, and both platforms quote the same files.
