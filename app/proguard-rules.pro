# R8 is on for release (app/build.gradle.kts). No app rules are needed: the
# app has no reflection, no by-name class lookups and no serialization
# framework, and its libraries (OkHttp, AndroidX, coroutines) carry their own
# consumer rules. A missing rule shows up as a build failure listing it in
# app/build/outputs/mapping/release/missing_rules.txt.
