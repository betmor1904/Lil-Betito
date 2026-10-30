# Turtle Shot: handoff (Oct 30, 2026)

Owner: Alberto (Procreate artist). Builds on an Android phone with Termux. Wants short, plain answers and exact Termux commands.

## Repo and build
- GitHub: betmor1904/Lil-Betito (old name lil-betito). Work branch: `crochet-on-pool-aim` (built on `pool-aim`, NOT main). Other branches: main (Oct 13 random mazes), bank-shots, pool-aim, crochet-betito (wrong base, ignore).
- Package com.example.spray, applicationId com.betmor.lilbetito, Kotlin, single canvas-drawn views. No local Android SDK: CI (GitHub Actions "Build APK") compiles on every push and uploads artifact `screen-skater-apk` (app-debug.apk). CI logs are NOT downloadable from the sandbox (403), so review code carefully for compile errors before pushing.
- Last good build: run 41145749750 (all features below). Commit messages end with the Co-Authored-By / Claude-Session lines from the session reminder.
- User installs: `~/getgame <runId>` in Termux (script downloads artifact to ~/storage/downloads/game-<id>, copies to TurtleShot-<id>.apk), then `termux-open ~/storage/downloads/TurtleShot-<id>.apk` or tap the file in Files. Stop/uninstall old game first if install fails.
- Kotlin pitfall: properties declared after an `init` block are not initialized inside it. Paint shaders are set in onSizeChanged.

## What exists (all in app/src/main/java/com/example/spray/)
- Game.kt (rules, 30 hand-made holes in Levels.kt, shell kinds BOUNCY/RUBBER/MEGA/SPONGE/TWIST + bomb), GameView.kt (drawing), MenuView.kt, MainActivity.kt, GameActivity.kt.
- TurtleRig.kt: layered crochet Betito (5 parts, pivots) + TurtleAnim (paddle, idle gestures, aim crouch, launch stretch, cheer, droop, bump tuck). Drawn 1.5x bigger in levels (hitbox unchanged).
- Yarn.kt: loads/draws all yarn art: knit sea/wall/rock/hole shaders, 4 shell sprites (+2 recolored placeholders for RUBBER green and SPONGE grey), bomb, crochet shark, 8 turtle pose sprites (swim_forward, swim_up, look_up, wave, happy_wave, spin, swim_away, wave_goodbye), 8 yarn water decals, 8 comic patches (cloud, burst, BING, POW, ZAP, BOOM, WHAM, BAM). Falls back to old shapes if loading fails.
- Shell skills (Game.shellSkill): lightning freezes sharks 4s; rubber = 2.2s no drag glide; flames burn coral plugs; sponge stops dead; waves bend path 40 deg.
- Comic patches fire on: full-power launch (POW), hard rock hit (WHAM), shark bump (BAM), target (BING), shell hits, bomb (BOOM), wall opening (burst).
- Level clear: big Betito fly-by (spin + happy wave) across screen (GameView.drawFlyBy). Menu: crochet shark swims across, Betito swims/waves/spins with bubble wake, yarn decals, shells in corners.
- ReefView.kt / ReefActivity.kt = REEF SORT (menu button teal): shell-sort puzzle, 4 per ledge, generated solvable by reverse scramble, move limit, shark locks a ledge every 5 moves with ghost preview, ZAP (swap tops, from lvl 2), WAVE (flip ledge, from lvl 4), 1 undo. Level saved in prefs reef_level.
- ChessView.kt / ChessActivity.kt = REEF CHESS (menu button red): 6x6 board, shells as pieces (lightning=rook, waves=bishop, flames=knight, sponge=1 step uneatable), sharks step toward nearest turtle and eat, red squares show next-turn danger, win by reaching right column or eating all sharks, 3 undos, 5 hand-made levels (repeat). prefs chess_level.
- Icons/covers: launcher icon is crochet turtle on knit; store/crochet-feature-graphic-1024x500.png, crochet-cover-square-1024.png, crochet-icon-512.png (original store/cover-* untouched).
- All art lives in app/src/main/res/drawable-nodpi/ (crochet_*, yarn_*, pose_*, decal_*, pop_*, crochet_shark). Source cutouts were made with OpenCV chroma-key from the user's AI-made crochet sheets; originals are not stored, only the final PNGs.

## Known gaps / ideas (user said next step: "fix the games up")
- NOTHING has been play-tested on a device except the user's own quick looks; expect bugs, balance and layout issues. Ask the user for screenshots/feedback first.
- Octo (the helper octopus) is still the old purple cartoon, not crochet. Menu buttons are flat colors, not knit patches.
- RUBBER/SPONGE shells are recolored placeholders. Knit textures are generated, not painted; user can paint real tileable swatches in Procreate.
- Level-clear fly-by + comic patches never verified visually. Camera zoom in levels was not done (only bigger turtle).
- Reef Sort: tune move limit, shark lock, add hidden shells, more special pieces (flames removes a stuck shell, sponge blocker).
- Reef Chess: only 5 levels, simple shark AI, no animations for turtles beyond hop; ideas: smarter sharks, push-back piece, more boards, 2-player mode.
- Pose sprites swim_up/look_up/spin/swim_away/wave_goodbye are only lightly used.
- Merge crochet-on-pool-aim into pool-aim/main when the user is happy.
