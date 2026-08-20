# Gradle Bootstrap Note

This seed ZIP uses a small shell bootstrap as `./gradlew` so the repository can be uploaded without embedding a binary wrapper JAR.

On the first engineering pass:

1. run `./gradlew --version`,
2. run `./gradlew wrapper --gradle-version 9.5.0`,
3. commit the official generated:
   - `gradlew`,
   - `gradlew.bat`,
   - `gradle/wrapper/gradle-wrapper.jar`,
   - `gradle/wrapper/gradle-wrapper.properties`.

After that, delete this note if desired.
