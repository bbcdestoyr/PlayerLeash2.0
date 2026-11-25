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
- Right-click with the Pet Controller (a compass) to open the menu.
- Only the owner can free a pet: Disown in the menu, or `/unleash [player]`. Staff with `playerleash.admin` can free anyone. Set `pet-can-unleash-self: true` in `config.yml` to let pets free themselves.
- `/playerleash reload` reloads `config.yml`.

- Cage (menu): locks a pet in a 3x3 glass cage. They can still use commands but can't break out, break blocks or teleport out. The cage opens with the same button, when you Disown them, or when either of you leaves. If the server crashes while a cage is up, the glass stays and you remove it by hand.
- Maid dress (menu): click the pink dye to put it on or take it off, right-click to change colour (Black & white, Baby pink, Lavender, Mint, Sky blue, Navy, Red, Pale lilac). Pick the default in `config.yml` with `dress-style`. If it looks wrong, try `dress-follow: teleport` or adjust `dress-height-adjust`.
- View inventory (menu): shows what a pet is carrying. View only.
- Pets stay owned when either of you leaves: the leash goes back on when you are both online again. Disown (or `/unleash <name>`) works even while they are offline.
- Leashed pets can't fly (`block-flying-while-leashed`).
