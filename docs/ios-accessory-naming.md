# AccessorySetupKit camera names

## Before discovery

On iOS, Add Camera opens `SharedPairingPreparationScreen` before the system
picker. Its English/German instructions explain how to enable Bluetooth, start
pairing on the camera, and keep that screen open. Only **Search for camera**
opens the picker; Back and Need help do not start discovery. The existing
troubleshooting guide returns to the preparation screen when opened from here.

`PairingPreparationState` disables repeat searches while a picker is pending and
re-enables the controls after it returns. An unsuccessful picker result leaves
the instructions open for retry; a successful result returns to Devices. The
screen and state live in commonMain for reuse by Android, but Android's current
setup flow is unchanged. Run the `*PairingPreparation*` tests for these guards.
