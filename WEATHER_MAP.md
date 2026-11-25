# Weather map prototype

1. Enable weather and configure a location under **Controls → Weather**.
2. On **Clock & Favourites**, select **Radar** beside the lower UV/rain readings.
3. First use shows what is shared with Windy. Select **Open maps** to remember that choice.
4. **Radar** opens observed rain. **Forecast** opens Windy’s modelled Rain, thunder layer and its original forecast timeline.
5. Drag or zoom the provider map. **Recentre** returns to the configured weather location and zoom. **Reload** retries the current preset. **Back** returns to Home.

The native Radar/Forecast buttons are layer presets, not a readback of changes made through Windy’s own layer selector. Select a preset again to restore it. AVC enlarges the original Play/Pause and zoom buttons on both layers. Windy supplies the map, timeline, timestamps and legend. Forecast uses the provider timeline; there is no custom Now/+1/+2/+3-hour control, and forecast rainfall is not a three-hour radar prediction.

## Location and privacy

The viewer reuses the existing foreground weather location resolver. A saved city requires no device-location permission. Automatic mode still requires the separate weather-location consent and Android permission; a saved place is used and labelled when automatic location is unavailable. With no usable place, the viewer offers Weather settings instead of substituting coordinates.

Windy receives the weather coordinates rounded to two decimal places, the normal network request information and any provider-site data. The map does not receive OBD readings. The location-only path performs no extra Open-Meteo forecast request or cache read. Leaving the viewer stops location resolution and destroys its WebView. Returning resolves the location again. The map does not follow the car while being viewed.

The info icon opens map details and Windy’s privacy policy. **Ask before opening maps again** removes the saved map opt-in and closes the map immediately. Weather’s existing Open-Meteo attribution remains in its own info panel.

The viewer has no native JavaScript bridge, file/content access or geolocation permission. It blocks mixed content, denies web permission requests and rejects TLS errors. Native navigation is restricted to the fixed HTTPS embed; approved, user-selected provider/attribution links open the browser. The remote map still runs Windy’s original JavaScript and requires a working Android System WebView and internet connection.

## Validation and distribution

The embedded viewer was developed for private use. Public distribution of this native integration has not been cleared with Windy. [Windy’s terms](https://account.windy.com/agreements/windy-terms-of-use) · [Windy’s response on native app distribution](https://community.windy.com/topic/25201/is-embedding-a-widget-in-a-free-app-considered-commercial-use)
