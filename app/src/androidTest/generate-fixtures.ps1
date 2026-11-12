$ErrorActionPreference = 'Stop'
$assetDirectory = Join-Path $PSScriptRoot 'assets'
New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null
& ffmpeg -hide_banner -loglevel error -nostdin -y -f lavfi -i 'testsrc2=size=320x240:rate=10:duration=9' -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=9' -c:v libx264 -preset veryfast -crf 35 -pix_fmt yuv420p -c:a aac -b:a 48k -movflags +faststart (Join-Path $assetDirectory 'h264-aac-9s.mp4')
if ($LASTEXITCODE -ne 0) { throw 'H264/AAC fixture generation failed' }
& ffmpeg -hide_banner -loglevel error -nostdin -y -f lavfi -i 'testsrc2=size=320x240:rate=10:duration=9' -f lavfi -i 'sine=frequency=660:sample_rate=48000:duration=9' -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -crf 42 -b:v 0 -pix_fmt yuv420p -c:a libopus -b:a 32k (Join-Path $assetDirectory 'vp9-opus-9s.webm')
if ($LASTEXITCODE -ne 0) { throw 'VP9/Opus fixture generation failed' }
