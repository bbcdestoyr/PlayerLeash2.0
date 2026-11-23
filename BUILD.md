# Building PlayerLeash

You need a Java 21 JDK and Maven. Then, in this folder:

    mvn package

The finished plugin is `target/PlayerLeash-1.0.0.jar`. Drop it in your server's
`plugins/` folder and restart (Paper 1.21.11).

## No Maven on your PC? Let GitHub build it

1. Make a free GitHub repo and upload everything in this folder (including `.github`).
2. Open the repo's **Actions** tab. The "Build plugin" run starts by itself.
3. When it's green, open the run and download the **PlayerLeash** artifact. Unzip it to get the jar.

## In game

- Right-click a player holding a Lead, or `/leash <player>`. They get clickable Accept / Decline.
- `playerleash.force` (ops by default) skips the prompt.
- Right-click with the Pet Controller (a stick) to open the menu.
- `/unleash` frees you if you are the pet. As the owner, it releases your pets.
- `/playerleash reload` reloads `config.yml`.
