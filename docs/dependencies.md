# Dependency audit

The initial npm audit reports GHSA-vfj7-8cjw-p6xm in `braces` through React Native/Metro/CLI tooling. At the time of implementation, npm lists 3.0.3 as the latest braces version and the advisory affects <=3.0.3. There is no compatible patched override available.

`npm audit --omit=dev` reports 21 high entries, largely propagated through this dependency chain. This is not a claim of 21 independent issues or of exploitation in the installed APK. Metro/CLI are Node build tools; the installed release embeds its JavaScript bundle and does not run a Metro server. Do not expose the development server to untrusted clients or feed untrusted glob patterns to tooling. Recheck the advisory before distribution and update compatible patches when available.

The unmodified React Native Android template opts out of AGP 9's new DSL and built-in Kotlin. Builds warn about removal of those compatibility switches in AGP 10. The wrapper and packages are pinned, and the lockfile is included; do not upgrade AGP independently of React Native compatibility.


Settings sliders use pinned `@react-native-community/slider` 5.2.1, an MIT-licensed native control. Official usage and compatibility documentation: https://github.com/callstack/react-native-slider .
