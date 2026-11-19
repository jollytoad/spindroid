**SpinDroid**

Copyright 2026 Mark Gibson

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Third-party works

The clock filter in
`sendspin/src/main/kotlin/app/gubbins/sendspin/time/TimeFilter.kt` is a
port of the Sendspin time filter reference implementation.

- Source: https://github.com/Sendspin/time-filter
- Copyright: the Sendspin time filter authors
- License: Apache License, Version 2.0

The C++ original was ported to Kotlin and adapted for this project.

The Noise test vectors in
`sendspin/src/test/kotlin/app/gubbins/sendspin/noise/Kkpsk2VectorTest.kt`
come from the cacophony corpus, which is released into the public domain
(https://github.com/haskell-cryptography/cacophony).
