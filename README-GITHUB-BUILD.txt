ORE ESP - GET A READY-TO-INSTALL JAR

This ZIP includes a GitHub Actions workflow that tries to compile the mod for you.
You do not need to install Java or Gradle on your own computer.

PART 1 - MAKE A GITHUB REPOSITORY
1. Go to https://github.com and sign in (or create a free account).
2. Open https://github.com/new
3. In Repository name, type: oreesp
4. Choose Public.
5. Leave the other settings alone and click Create repository.

PART 2 - UPLOAD THE MOD FILES
1. Open the new empty repository.
2. Click "uploading an existing file" (or Add file > Upload files).
3. Open the extracted oreesp folder from this ZIP on your computer.
4. Select ALL files and folders INSIDE oreesp, including the hidden .github folder.
   IMPORTANT: Upload the contents of oreesp, not the outer oreesp folder itself.
5. Drag them into the GitHub upload page.
6. Scroll down and click "Commit changes".

PART 3 - DOWNLOAD THE JAR
1. In your repository, click the Actions tab.
2. Click the newest "Build Ore ESP" run.
3. Wait until it finishes. A green check means success.
4. Scroll to the bottom of that run page to Artifacts.
5. Click "Ore-ESP-JAR" to download it. Extract that downloaded ZIP.
6. Copy the file ending in .jar into %APPDATA%\\.minecraft\\mods
7. Launch the Fabric 26.3 profile.

IF IT FAILS
If the Actions run has a red X, open it and send a screenshot of the error. The source may need code changes; the workflow cannot guarantee the project compiles.

The JAR is a client-side mod. Install the matching Fabric API for Minecraft 26.3 as well, if the game does not already have it.
