# Lumen

A custom home screen for Mitch's Bedroom TV (TCL Google TV, Android 11).

- `app/src/main/assets/index.html` is the whole interface. Edit it to change the look.
- `MainActivity.java` is a thin shell: it shows the page full screen, answers the Home button,
  and gives the page the TV's app list, icons, recent apps and HDMI inputs.
- Every push to `main` builds a signed APK and publishes it under **Releases**.

## Install or update (from the PC with tvctl.sh)

```bash
~/tvctl.sh install-github <your-github-name>/lumen
~/tvctl/platform-tools/adb -s 192.168.6.29:5555 shell appops set com.mitch.lumen GET_USAGE_STATS allow
~/tvctl/platform-tools/adb -s 192.168.6.29:5555 shell cmd package set-home-activity com.mitch.lumen/.MainActivity
```

## Undo

```bash
~/tvctl.sh undo
```

The signing key in `app/lumen.keystore` is a personal sideload key so every build can update the previous one.
