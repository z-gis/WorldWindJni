Add-Type -AssemblyName System.Drawing

$src = 'K:\dev\WorldWindKotlin-develop\WorldWindKotlin-develop\worldwind\src\commonMain\moko-resources\images\worldwind_worldtopobathy2004053@1x.png'
$out = 'K:\dev\MobileMap\WorldWindJni\worldwind-tutorials\src\main\assets\tiles\topo'
$tileSize = 256
$maxZoom  = 3

$type = @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
public static class TileCutter {
  public static int Cut(string srcPath, string outDir, int tileSize, int maxZoom) {
    int count = 0;
    using (Bitmap src = new Bitmap(srcPath)) {
      int sw = src.Width, sh = src.Height;
      Rectangle rect = new Rectangle(0, 0, sw, sh);
      BitmapData sd = src.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
      byte[] sbuf = new byte[sd.Stride * sh];
      Marshal.Copy(sd.Scan0, sbuf, 0, sbuf.Length);
      src.UnlockBits(sd);
      for (int z = 0; z <= maxZoom; z++) {
        int n = 1 << z;
        for (int x = 0; x < n; x++) {
          for (int y = 0; y < n; y++) {
            Bitmap bmp = new Bitmap(tileSize, tileSize, PixelFormat.Format32bppArgb);
            Rectangle tr = new Rectangle(0, 0, tileSize, tileSize);
            BitmapData td = bmp.LockBits(tr, ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            byte[] tbuf = new byte[td.Stride * tileSize];
            for (int py = 0; py < tileSize; py++) {
              double fy = ((double)y + (py + 0.5) / tileSize) / n;
              double lat = Math.Atan(Math.Sinh(Math.PI * (1.0 - 2.0 * fy))) * 180.0 / Math.PI;
              int syy = (int)Math.Round((90.0 - lat) / 180.0 * (sh - 1));
              if (syy < 0) syy = 0; if (syy >= sh) syy = sh - 1;
              for (int px = 0; px < tileSize; px++) {
                double fx = ((double)x + (px + 0.5) / tileSize) / n;
                double lon = fx * 360.0 - 180.0;
                int sxx = (int)Math.Round((lon + 180.0) / 360.0 * (sw - 1));
                if (sxx < 0) sxx = 0; if (sxx >= sw) sxx = sw - 1;
                int soff = syy * sd.Stride + sxx * 4;
                int toff = py * td.Stride + px * 4;
                tbuf[toff + 0] = sbuf[soff + 0];
                tbuf[toff + 1] = sbuf[soff + 1];
                tbuf[toff + 2] = sbuf[soff + 2];
                tbuf[toff + 3] = 255;
              }
            }
            Marshal.Copy(tbuf, 0, td.Scan0, tbuf.Length);
            bmp.UnlockBits(td);
            string dir = System.IO.Path.Combine(outDir, z.ToString());
            System.IO.Directory.CreateDirectory(dir);
            string fpath = System.IO.Path.Combine(dir, x + "_" + y + ".tile");
            bmp.Save(fpath, ImageFormat.Png);
            bmp.Dispose();
            count++;
          }
        }
      }
    }
    return count;
  }
}
'@

Add-Type -TypeDefinition $type -ReferencedAssemblies System.Drawing

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$n = [TileCutter]::Cut($src, $out, $tileSize, $maxZoom)
Write-Output ("tiles generated = " + $n)
