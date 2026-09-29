v1.9.7 — Your own server over http, a clearer start without a key

- Speech and text-processing addresses can now be plain http://, for a server of your own on your home network. The settings show a warning under such an address, because everything sent to it travels unencrypted
- Without an API key, the microphone now opens the settings straight away instead of recording first and failing afterwards; the resend key does the same
- On a device without a browser, links in the app no longer close it: the link is copied instead
- Cancel during an update download now really stops it
- While a recording is made and sent, it now stays in the app's private storage; older versions used a folder that other apps could read on Android 9 and earlier
