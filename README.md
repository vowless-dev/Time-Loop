# Time Loop
[![Static Badge](https://img.shields.io/badge/GitHub-Release-green?logo=Github)](https://github.com/VLTNOgithub/Time-Loop/releases/latest)
[![Static Badge](https://img.shields.io/badge/Modrinth-Release-green?logo=Modrinth)](https://modrinth.com/mod/timeloop/version/latest)

A mod that 'loops' time by using the Motion Capture Mod.
*Motion Capture mod by mt1006. Inspired by [this Tombino video](https://www.youtube.com/watch?v=i602-oh0a0c). Original datapack by Penguin Mafia.*

# Installation
**Requires [Motion Capture Mod](https://modrinth.com/mod/motion-capture/) 1.4-alpha-10**

The mod works in singleplayer and multiplayer.

# Usage
Simply use commands to configure the loop.

**/loop**
- `start` - Start the loop.
- `skip` - Skip the loop and advance to the next iteration.
- `stop` - Stop the loop.
- `reset` - Reset the loop and go back to the first recording. **This doesn't delete the recordings as of yet but it's being worked on.**
- `status` - Shows the status of the loop in chat.
- **settings**
    - `maxLoops [disabled]` - Sets the maximum amount of loops.
    - `loopType [ticks] [6000]` - Sets the type of loop.
    - `modifyPlayer [target_player] [nickname] [skin_from_file|skin_from_name|skin_from_mineskin] [skin]` - Changes a looped player's nickname and skin.
    - `setRewindType [none]` - Sets the rewind type.
    - **toggles**
        - `trackTimeOfDay [false]` - Toggles tracking the time of day during loops.
        - `trackItems [true]` - Toggles tracking dropped items during loops.
        - `trackInventory [false]` - Toggles tracking the player's inventory during loops.
        - `showLoopInfo [true]` - Toggles a bar at the top of the screen showing the amount of ticks/time left until the next loop.
        - `displayTimeInTicks [false]` - Displays the ticks instead of HH:MM:SS on the info bar.
        - `trackChat [true]` - Toggles tracking chat during loops.
        - `hurtLoopedPlayers [false]` - Toggles being able to hit looped players.
    - **voice** (Requires simple voice chat to be installed)
        - `trackVoice [true]` - Toggles tracking simple voice chat during loops.
        - `showPlayerVoiceIcon [true]` - Toggles whether or not to show the speaking icon for looped players.

## Loop Types
- `manual` ➔ Only loops when you use the `skip` command
- `ticks x` ➔ Loops every *x* ticks. Defaults to 6000 ticks (5 minutes)
- `time_of_day x` ➔ Loops when the time reaches the set time of day, *x*. Defaults to 13000
- `sleep` ➔ Loops when a player sleeps
- `death` ➔ Loops when a player dies

## Rewind Types
- `none` ➔ Doesn't rewind
- `start_position` ➔ Rewinds players to their position when the loop started
- `join_position` ➔ Rewinds players to their position when they joined
- `spawn_position` ➔ Rewinds players to the world's spawn position

## Max Loops Types
- `disabled` ➔ Disables the setting
- `stop_loop` ➔ Stops the loop after *x* loops
- `keep_newest` ➔ Keeps the newest *x* loops
- `keep_newest_delete` ➔ Keeps the newest *x* loops and deletes their recording files

## Skin Types
- `skin_from_name [x]` ➔ Sets the skin from the given name, *x*. If no name is given, it defaults to the player's name.
- `skin_from_mineskin x` ➔ Sets the skin from the given url, *x*
- `skin_from_file x` ➔ Sets the skin from the file *x*. Skin location is: `saves/<World name here>/mocap_files/skins/`

# Support
If you need help or encounter an issue, don't hesitate to ask someone in the discord: https://discord.gg/nzDETZhqur
