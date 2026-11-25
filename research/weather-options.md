# Optional seven-day clock forecast

Researched and implemented 21 November 2026; extended 23 November 2026. No real device location has been queried during development.

## Provider choice

| Option | Fit for this head unit |
| --- | --- |
| **Open-Meteo** | Selected. Its daily endpoint supplies seven days of conditions and high/low temperatures directly. It also offers city/postcode search. The free API suits this private, non-commercial app without an embedded key. |
| **MET Norway Locationforecast** | A viable global alternative with nine-day forecasts. It requires identifying the client and obeying its cache/update policy. Its time-series representation would require more daily aggregation work here. |
| **OpenWeather One Call 3.0** | Offers a broader weather product, but requires API-key and subscription setup. That adds account/key management to this private head-unit feature. |

Primary sources: [Open-Meteo forecast documentation](https://open-meteo.com/en/docs), [geocoding documentation](https://open-meteo.com/en/docs/geocoding-api), [usage and privacy terms](https://open-meteo.com/en/terms), [MET Norway Locationforecast](https://api.met.no/weatherapi/locationforecast/2.0/documentation), [MET terms](https://docs.api.met.no/doc/TermsOfService), [OpenWeather One Call](https://openweathermap.org/api/one-call-3).

## Native implementation

- **Controls → Weather:** explicit On/Off state, automatic versus saved place, searchable city/town/postcode with region/country disambiguation, Celsius/Fahrenheit, preview, refresh and provider credits.
- **Clock & Favourites:** a compact native seven-day row below any of the four clock faces; daily vector weather icons and high/low temperatures. The clock uses the remaining height; the Home page remains fixed.
- Weather defaults **off**, with no configured automatic location. Off cancels the repository and prevents both automatic forecast updates and place searches.
- Choosing a saved place selects manual mode. Automatic mode first requires its own concise consent dialog; an already-granted Bluetooth scanning location permission is insufficient.
- After consent, a foreground device fix may use Android network location or GPS. Cached fixes must be no older than five minutes; a new fix has an eight-second timeout. No IP geolocation or background location service is used.
- Coordinates are rounded to two decimal places before forecast requests or private cache persistence. If device location is unavailable, the saved place is used with a visible fallback label. With no saved place, the user is asked to choose one.
- The first configured forecast in each app process requests fresh data immediately. An interrupted or failed request does not mark startup complete. Failures retry at 5, 10, 20, 40 and then 60 seconds, and a newly available network triggers an earlier retry. A successful forecast schedules its next update one hour after fetch time while visible. Cache data remains visible during refresh; update age and failure/fallback details are in the info panel. Data older than 24 hours is hidden. Local-midnight coverage is checked using the forecast timezone, so yesterday is not shown as the first of seven days.
- Current relative humidity, 10 m wind speed/direction, daily maximum UV index and daily maximum precipitation probability are requested with explicit km/h wind units. Optional missing or out-of-range values remain unavailable. Missing temperatures and unknown conditions remain unavailable. No generated weather values substitute for failed requests.

## Privacy, attribution and service use

Forecast requests and manual search terms go directly to Open-Meteo over HTTPS. The provider says request logs can contain coordinates and be retained for up to 90 days; this is disclosed before automatic-location consent and in Data & privacy. Manual city selection works without device-location permission. No analytics, advertising, third-party SDK, VIN, Bluetooth identity or raw diagnostic frame is sent with weather requests.

Weather data is attributed to **Open-Meteo · CC BY 4.0**. Place data comes from **GeoNames** through the provider. The settings include source, privacy and licence links. This implementation uses the provider's free non-commercial service; commercial distribution would require revisiting its terms/account plan.

The forecast cache is private and size-limited. HTTP responses are capped at 256 KiB with connect/read timeouts and generation-based cancellation. It is a forecast display, not a safety-alert service.

## Verification

`tests/java/WeatherRepositoryTest.java` runs against Android platform JSON/Looper through `app_process`. Thirty-five checks passed with fake settings/context: no GPS, Bluetooth, app data or network access. They exercise rounding, settings/consent gates, disabled runtime, missing data, malformed/truncated responses, geocoding disambiguation, date coverage, cache age, and response bounds. Native screen and live public-city API checks are handled by the root integration workflow. Actual vehicle GPS behavior remains a physical-device acceptance gate.

Twelve host timing checks cover hourly scheduling, cache age, bounded failure backoff, network retry, loading and inactive guards. The API29 emulator recovered a fresh provider response 6.44 seconds after Wi-Fi returned; this is local emulator evidence, not a physical CC3 timing guarantee. The four clock faces and four palettes were visually checked at the measured CC3 profile.
