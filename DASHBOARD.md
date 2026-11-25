# AVC Dashboard layouts

Open **AVC Manager → Appearance**. Select a layout and tap **Full-screen preview**. The preview uses the Home renderer with the selected colour and pending background, clock face, tile count, panel-heading preference and chosen apps. **Back to Appearance** returns to the pending selection. **Apply to Home** saves the pending layout choices.

The six Home compositions fit the [recorded CC3 profile](tools/cc3-display-profile.json) without vertical or horizontal scrolling: **1280 × 720, 160 dpi, 1.5× text**, a 60-pixel TEYES top bar, and app bounds **(0, 60)–(1280, 720)**. App icons are 64 dp; Home tiles and dock controls target at least 76 × 76 dp.

| Layout | Home composition | Design source |
| --- | --- | --- |
| Radio & Media | Radio, media and selected extra favourites above navigation and phone projection. | [TEYES CC3 update history](https://cc3.teyes.cn/en/update.html) |
| Four-Panel Grid | Navigation, phone connection, audio, and favourites with a Controls shortcut occupy four cards. | [TEYES CC4 Freestyle Mode video guide](https://www.teyes.com.au/pages/cc4-pro-how-tos) |
| Maps & Side Dock | A navigation card beside media and phone panels, with a selected favourite in the driver-side dock when available. | [Apple CarPlay Dashboard guide](https://support.apple.com/en-ie/guide/iphone/iph5f6b2beb5/ios) |
| Maps & Bottom Dock | Navigation and media above a dock containing phone projection, All apps and AVC Manager. | [Google's Android Auto redesign](https://blog.google/products-and-platforms/platforms/android/best-of-android-to-improve-your-drive/) |
| Large Shortcuts | Up to eight selected shortcuts. On the CC3, five or six use three columns and seven or eight use four. | [AGAMA Car Launcher guide](https://altercars.ru/agama/instructions/en.html) |
| Clock & Favourites | A configurable local clock beside four or six app slots. | [AGAMA Car Launcher guide](https://altercars.ru/agama/instructions/en.html) |

Navigation opens the installed Maps or Waze app. Tap the arrow or its label in Maps & Side Dock to open navigation. When both apps are selected, Waze also has a separate shortcut. Phone projection opens CarLink, and media shortcuts open their installed apps.

## Apps and favourites

**All apps** is permanently available on every Home layout, in its bottom or right-side dock. A cog beside it opens AVC Manager’s Appearance page. Horizontal dock actions have equal widths; Maps & Side Dock groups All apps and the cog at the bottom of its driver-side rail. All apps opens every installed app with an exported launcher entry, including apps outside the favourites and AVC Manager. Local search, Clear and Close stay available; the drawer scrolls through long lists. Closing it returns to the same Home layout.

In **Choose Home shortcuts**, tap **N selected** at the top right to see your current picks from every source in Home order. Tap a checked shortcut to remove it from the draft. Tap the count again to return to the previous tab, or tap a source to browse it. **Save** applies your changes.

**Choose Home shortcuts → Arrange** lets you tap a shortcut and then its new numbered position. The intervening shortcuts shift one place. Tap **Save** to keep the order or **Cancel** to discard it. On Clock & Favourites and Large Shortcuts you can also hold a tile on Home, tap its destination and save. Positions run left to right, then top to bottom. On layouts with dedicated panels the order applies within each panel.

**Choose Home shortcuts** selects installed apps and AVC Manager functions available to the layouts. **Reset defaults** restores the available navigation, audio and projection shortcuts. Each composition limits its visible tiles to keep Home fixed; additional apps remain accessible through All apps.

For **Clock & Favourites**, open **Appearance → Clock & tiles**:

- **Four tiles · 2 × 2** uses two columns and two rows.
- **Six tiles · 3 × 2** uses three columns and two rows on the reference screen.
- An empty **Add shortcut** (+) slot opens the Home shortcut picker. Choose more apps and save to fill the slots.
- When panel headings are shown and more favourites are selected than the visible capacity, the heading shows the visible and selected counts.

## AVC Manager function shortcuts

On Clock & Favourites, tap an empty **Add shortcut** (+) tile. The shared **Home shortcuts** picker includes these sources:

- **AVC Manager:** Vehicle dashboard, Entertainment, Music, Video, Sound, Wireless, Firewall, Appearance and Controls. Each uses its matching Manager icon.
- **Installed apps:** the existing launchable apps and their original labels/icons.
- **Vehicle metrics:** individual readings from the active profile.

Choose **AVC Manager → Music** or **Video** to pin a direct link to that Entertainment library. Either shortcut opens the chosen tab without starting playback.

Selections remain in the draft while switching sources. Tap **Save** to place the chosen shortcuts on Home; **Cancel** discards draft changes. The existing favourites are retained. Each layout displays up to its tile capacity. Use **Arrange** to move a shortcut into a visible position while keeping the others selected. **Reset defaults** restores the original app defaults and removes added function selections.

**Vehicle dashboard** always opens Dashboard, including after the last Vehicle page was Sensors or Faults. **Entertainment** opens the integrated player section. Shortcuts open the normal sections without pressing playback, firewall or debugging actions. Existing configured OBD auto-connect and firewall persistence keep their normal behavior.

**Vehicle metrics** in the shortcut picker lists readings for the active vehicle profile. Select up to four and tap **Save**. An existing installation starts with its previous shortcut order. Use **Arrange** to place a reading alongside your apps or move it into a visible position. Tap a reading to open its history. The Home and Vehicle screens share one adapter session. Auto-connect uses the adapter saved under **Controls → Vehicle**. If you connect manually, switch directly from Vehicle to Home to continue that session. A last reading is labelled when it stops updating.

The same tiles render in Home and full-screen preview. **Appearance → Choose Home shortcuts** opens this picker when all Home slots are filled. App package identifiers remain unchanged in saved preferences; function identifiers occupy a separate namespace and do not create fake installed apps or extra All apps entries.

## Appearance

The overview has four entries: **App colours**, **Background**, **Clock & tiles**, and **Sidebar**. These open in-app choosers. The fixed action bar contains Apply to Home, Full-screen preview and Choose Home shortcuts.

**App colours** applies one palette to Manager, full-screen preview and Home. Midnight Blue takes its indigo panels and cyan highlights from the CC3 Home shown by TEYES. Obsidian takes its graphite panels from the CC4 map and media screen, with ice blue for selected controls. Dusk and Copper provide plum and warm alternatives. Each option shows its accent next to its name. Choosing a colour updates AVC Home and Manager immediately. A pending layout still needs **Apply to Home**. [TEYES CC3 reference](https://www.teyes.com.au/cdn/shop/files/cc3_thumnail_teyes_australia.jpg) · [TEYES CC4 reference](https://www.teyes.com.au/cdn/shop/files/S289070a0a4ff466dbc81e713ea9a2d6eE_360x.webp?v=1780001784)

**Background** presents nine choices at once on the reference profile:

| Choice | Content |
| --- | --- |
| Landscape | A photograph paired with the selected colour theme. |
| Abstract | An illustration paired with the selected colour theme. |
| Contour | Original theme-coloured contour artwork. |
| Aurora glow | Original theme-coloured ambient artwork. |
| Orbital sunset | NASA-credited photograph. |
| Aurora Earth | NASA-credited photograph. |
| Moonrise | NASA-credited photograph. |
| Your photo | An image chosen through Android's file picker, with retained read access. |
| Solid colour | The theme's plain background. |

Bundled images and artwork load locally. **Image credits** in the chooser lists their sources. Your photo can use a downloaded image whose usage terms permit it.

**Clock & tiles** offers **Digital**, **Analogue**, **Split**, and **Stacked** faces for Clock & Favourites. The clock follows the device's local time, date and 12/24-hour preference. Face and tile-count choices stay pending until Apply to Home. Cancel in the clock chooser restores the choices from when it was opened.

**Panel headings** in the same chooser has **Show headings** and **Hide headings**. It applies to all six Home layouts, including Local time, Favourites, Navigation and Media card titles. Existing installations start with headings shown. Select **Done**, preview if desired, then **Apply to Home** to save. Cancel restores the earlier draft. App labels and weather readings keep their names. The timezone info icon sits beside the date with either choice.

## Home app and controls

**On Home now** identifies the layout opened by the Home button. After the pending settings match the applied Home, the action button reads **Open Home**. If another launcher currently handles Home, Apply shows the setup steps. Tap **Continue to Android**, choose **AVC Dashboard**, and confirm **Set as default**. Later layout changes apply directly from Manager.

**Controls → Home app** lists separate installed launchers using friendly names and provides **Open Android Home settings** to change the default. Brightness and voice settings also remain under Controls.

## Build status

The local 0.3.0 candidate adds the year to the common date on all four clock faces. **Controls → Weather** optionally places a seven-day forecast beneath the clock. It starts off, supports a saved city or separately consented automatic location, and keeps the fixed Home composition. Weather and OBD are not present in installed 0.2.30.

0.2.30 is installed on the physical CC3, with the installed version and APK checksum verified. All six Home layouts fit the measured profile, all nine background choices are visible, Audio panels align, and All apps opens the installed launchable entries. Existing Home, display, audio and network settings were retained.. OBD monitoring and its dashboard concept are separate work and are not included in 0.2.30.


## Home navigation and playback —0.4.3

The **AVC Manager** title above the right menu is a Home button, with a house icon, a48dp touch target and an Open Home accessibility label. It opens the applied CC3 layout; it does not apply unsaved Appearance edits or change the default launcher.

All six layouts include a compact header shortcut while Entertainment has a current music item. It shows the actual track, artist if present and playing/paused/opening/error state. Tap it to return directly to Entertainment's Music tab. Stop removes it. From 0.5.9, Home’s header is present only while this shortcut is visible; idle Home uses the recovered height for content. Other players' metadata is not read.

The weather widget's top-right info icon opens **Weather details**, with Open-Meteo/GeoNames credit and provider/licence links. The former footer credit line is removed. The forecast still opens weather settings when tapped outside the icon. Attribution remains next to the forecast, with a48dp hit area around the small symbol. [Provider attribution guidance](https://open-meteo.com/en/licence).

From 0.5.2, a fresh forecast has no routine “Updated just now” line. The seven-day rows move closer to the place heading. Updating, saved-place, stale-forecast and update-failure notices still appear when relevant; freshness also remains available to accessibility services. Home and full-screen preview share this renderer.


## Clock weather and network settings — 0.5.3

On the measured 1280 × 720 display, Clock & Favourites gives the left clock/weather card the full content height. A 276 dp weather area displays seven days plus current humidity and wind, today’s maximum UV index and maximum precipitation probability. Missing fields remain unavailable. The permanent All apps control is centered beneath the favourites on the right, sharing their left/right alignment. Home and preview use the same renderer.

The top-right weather info panel contains update age, refresh cadence, saved-place/error status, metric time scopes and source/licence links. Ordinary update/status sentences no longer occupy forecast rows. A configured forecast requests fresh data at process startup and after failed requests when connectivity returns. Once successful, the next refresh is scheduled one hour from its fetched time while visible. An interrupted startup request does not count as a successful refresh. Disabled weather and unconsented automatic location retain their privacy guards.

Wireless separates Wi-Fi, Mobile data and Computer access using shared palette/button styles. The Wi-Fi card names IP address, subnet mask and default router. The access controls retain the existing owner-approved 15-minute debugging lease. **Controls → Vehicle** owns adapter and profile settings; **Vehicle** retains Dashboard, Sensors and Faults..


## Clock timezone and settings presentation — 0.5.4

The small info icon opens **Clock timezone**. In 0.5.11 it sits beside the date on each face. It reads the head unit’s current system timezone, showing its identifier, localized name, current UTC offset and whether daylight saving is active. It does not infer clock time from the forecast city or change the device’s timezone. The clock’s accessible description includes the same zone and offset. All four faces and the shared preview use the same information. The icon retains a48dp touch target.

**Controls** now uses five full-width navigation rows with equal spacing, trailing arrows and compact help icons. Display settings, Voice, Home app, Weather and Vehicle retain their existing destinations. **Vehicle settings** separates profile and live connection state into equal overview cards, with matching Connection and Adapters panels underneath. Technical protocol, transport, ECU and rate information is available from the Connection info icon. Adapter selection, Auto-connect and connection/session policy are unchanged.


## Sidebar style and Computer access — 0.5.5

**Appearance → Sidebar** is directly beneath **Clock & tiles**. Choose **Words** or **Icons**; it saves and changes the Manager sidebar immediately. Words is the default for existing installations. Icons use a narrower rail, with the same named destinations, selected states, accessible text and press-and-hold name hints. Sidebar position and Home layout remain as configured. Changing style does not discard a pending Home layout selection. The saved style survives app and head-unit restarts.

Wireless’s **Computer access** card separates its On/Off/pending state, time remaining and actions. Active sessions show a tabular countdown and an explicit selected state; Off shows the15-minute session limit. Stopping and expired sessions have distinct feedback. The existing confirmation, lease duration, authentication requirement and trusted-Wi-Fi shutdown policy are retained. This is a presentation change, with no automatic session enablement.

## Weather map and lower weather details — 0.5.8

Clock & Favourites keeps the seven-day forecast above an aligned two-by-two grid: Humidity, Wind with compass direction, UV today and Rain today. The separate **Radar** action sits beside these lower readings, with a 76 dp wide touch target and a visible label. It does not occupy the forecast heading. The Open-Meteo info icon remains in the heading’s top-right corner. The compact preview in Weather settings omits the lower details and Radar action; full-screen Home preview uses the same extended renderer as Home.

Map opens the optional native Windy viewer with Radar/Forecast layer presets, Recentre, Reload and Back. It uses the configured weather place, including the existing separately consented automatic-location fallback policy. Windy access has its own first-use opt-in. It does not send OBD readings or track the vehicle while closed. The location-only resolver does not fetch Open-Meteo forecast data. [Guide and limits](WEATHER_MAP.md).

## Home dock and optional panel headings — 0.5.9

The large layout name and former upper-right AVC Manager button are removed from Home. The content starts directly below the system bar. AVC Manager is now a cog button labelled **AVC Manager** in the existing All apps dock. On horizontal docks, actions have matching height, equal width and consistent spacing. The side-dock layout has All apps and the named cog action together at its foot, with the cog above the name to fit its narrower width. The cog returns to Appearance; in preview it returns to the pending Appearance draft. Full-screen preview retains its small context caption and explicit Back action.

Panel headings use the existing draft/preview/apply flow under Clock & tiles, preserve the saved choice across restarts and leave the timezone info action accessible when hidden. Weather’s lower map action and the permanent All apps entry are retained..

## Icon alignment and weather spacing — 0.5.10

All apps and AVC Manager dock icons are centred on the label’s font metrics rather than sitting on its baseline. The equal-width actions, text size and touch areas are unchanged. Clock weather grows from 276 to 308 dp, taking 32 dp from the clock. Location-to-forecast spacing increases by 16 dp; the remaining height makes the lower reading tiles roomier. Map’s icon and caption form one centred group beside the readings. Full-screen preview uses the same weather height; the compact Weather settings preview retains its 166 dp height and fits all seven days with the additional heading separation..
