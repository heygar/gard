# GarD Architecture Blueprint

## Overview
GarD is a modular, central orchestrator application designed to replace the Tandem T:Connect app. It manages the connection to the Tandem t:slim X2 pump, handles Nightscout syncing, and elegantly navigates the Bluetooth connection constraints of modern Continuous Glucose Monitors (CGMs).

## Core Responsibilities
1. **Pump Communication**: Establish and maintain a Bluetooth connection with the Tandem X2 using the `pumpX2` library.
2. **Bolus Orchestration**: Allow the user to initiate and manage boluses remotely.
3. **Data Routing**: Act as the singular source of truth for uploading data to Nightscout, preventing conflicts between multiple apps.
4. **CGM Bridging**: Ensure uninterrupted CGM data flow to Nightscout and smartwatches, regardless of which device (Pump or Phone) holds the primary Bluetooth connection to the sensor.

---

## Scenario A: Dexcom G7 (Pump ON or OFF)
*The Dexcom G7 has two independent Bluetooth channels: one medical slot, one smartphone slot.*

1. **Pump Connection**: The X2 connects to the G7's medical slot. Control-IQ functions normally.
2. **Phone Connection**: Juggluco connects directly to the G7's smartphone slot.
3. **Data Flow**: 
   - Juggluco receives the raw CGM data and broadcasts it locally (standard Android Intent).
   - GarD listens for this broadcast in the background.
   - GarD packages the data and pushes it to Nightscout.
4. **Watch Integration**: Juggluco handles direct-to-watch streaming natively, completely unaware of GarD.

---

## Scenario B: Libre3+ (Pump OFF)
*The Libre3+ has a single Bluetooth channel. Because the pump is off, the channel is free.*

1. **Phone Connection**: Juggluco connects directly to the Libre3+.
2. **Data Flow**: 
   - Juggluco broadcasts the data locally.
   - GarD listens and pushes to Nightscout.
3. **Watch Integration**: Juggluco handles the watch natively.

---

## Scenario C: Libre3+ (Pump ON)
*The X2 pump monopolizes the single Libre3+ Bluetooth channel. Juggluco is forcibly disconnected.*

1. **Pump Connection**: The X2 connects to the Libre3+. Control-IQ functions normally.
2. **Data Flow**:
   - GarD queries the X2 pump every 5 minutes using `pumpX2` (`CGMStatusRequest`).
   - The pump replies with the current CGM value.
   - GarD pushes this value to Nightscout.
3. **Watch Integration Challenge**: Because Juggluco is disconnected from the sensor, it stops updating the watch.
4. **Solution (Addressing User Concern)**:
   - *Option 1 (No Juggluco Config Changes)*: GarD can simply broadcast a standard `com.eveningoutpost.dexdrip.BgEstimate` Android Intent. Most watch faces (like G-Watch or xDrip+) automatically listen for this system-wide broadcast. Juggluco might ignore it, but the watch face doesn't care who sent it.
   - *Option 2 (Nightscout Watch Face)*: The easiest zero-config solution is to use a watch face that pulls directly from Nightscout via the web. Since GarD guarantees Nightscout is always up to date, the watch will always have data, bypassing Juggluco entirely during this specific scenario.

---

## Sensor Pairing Lifecycle (The "Pragmatic Shortcut")
Reverse engineering Abbott's NFC pairing SDK to securely hand off Libre3+ cryptographic keys to the X2 pump is outside the scope of GarD.

**The Workflow (Every 15 Days):**
1. Install/Unfreeze the official T:Connect app.
2. Scan the new Libre3+ sensor and allow it to pair with the X2 pump.
3. Immediately uninstall/freeze the T:Connect app.
4. Open GarD. GarD takes over for the remainder of the sensor's life.
