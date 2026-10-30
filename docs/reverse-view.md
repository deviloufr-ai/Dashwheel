# Reverse view

Dashwheel's reverse view draws over the head unit's reversing camera: guide lines that follow the steering wheel, the parking radar and a drawing of the car. Settings, Car, **Reverse view**. The car's own camera screen stays what it was: with the view off, nothing of this is there.

## What each setting does

| Setting | What it does | Needs |
| --- | --- | --- |
| **Dashwheel's reverse view** | The switch for all of it. Off, the car shows its camera as before. | The accessibility service (the only way to draw over the camera). |
| **On screen** | Camera and radar, camera only, or radar only. Can be switched on the reverse screen itself. | |
| **Radar look** and **car colour** | How the parking radar is drawn. | |
| **Camera picture** | *Car app*: the car's app shows the camera and Dashwheel draws over it. *Dashwheel (experimental)*: Dashwheel takes the camera from the car's app and shows it itself, which lets the second screen carry the picture. If no picture comes within a few seconds, the car app's is put back. | Root, the camera permission. Not in the Play edition. |
| **Second screen** | What a paired second screen shows in reverse. | A paired screen with Simple display off. |
| **Car app's lines** | *Keep*: the car app's lines stay under Dashwheel's. *Hide (experimental)*: only Dashwheel's, smoother. | Root. |
| **Voice while reversing** | *Quiet* (the default): Dashwheel's voice waits so you hear the parking sensors, and says what it had to say once reverse ends. Off, it keeps talking. This applies whether or not the reverse view is on. | |
| **Boot line** | A dashed line where the open tailgate would reach, and a warning when something is closer. | |
| **Line them up** | Select R, then tap Adjust on the reverse screen to match the lines to the road. **Reset the lines** puts them back. | |

## Back to the car's own camera

Whenever the view, Dashwheel's picture or the hidden lines are on, the sheet shows **Car's own camera** under the main switch. One tap puts everything back the way the car showed its camera before: its picture, its lines, no reverse view. It says what it did; if the car app's lines could not be brought back (no root), it says that too, and they come back with **Car app's lines: Keep** once root is there.

By hand, the same is: **Camera picture: Car app**, **Car app's lines: Keep**, then **Dashwheel's reverse view** off.

## The voice goes quiet in reverse

That is on purpose: while the car reverses, Dashwheel's voice holds what it has to say so the parking sensors are heard, then says it after. A turn instruction that went stale meanwhile is dropped rather than said late. **Voice while reversing** in the Reverse view sheet turns that off.

## Dashwheel's own picture: what to expect, and what to send

Taking the camera from the car's app takes a moment: the car app opens the camera first, is asked to let go of it, then Dashwheel opens it and waits for the first frame. On the unit it was built on, the picture is there in about a second; on others it can take a few, and if it does not come, the car app's picture is back within seconds. Dashwheel asks the camera for the size it offers, so a resolution changed in the car app's settings still gives a picture.

Each reverse with Dashwheel's picture writes one line to the debug log (Settings, System, Lab, **Debug log**): when the car app took the camera, when it let go, when Dashwheel's first frame came, at what size; or why it gave up. If the picture is slow or missing on your unit, send that log from the Lab with the unit's name: it is what a fix is made from.
