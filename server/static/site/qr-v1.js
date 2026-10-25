/*!
 * qr-v1.js: bộ tạo mã QR tối giản cho trang tải TruyenCloud (không gọi mạng).
 * Chế độ byte (UTF-8), mức sửa lỗi M, phiên bản 1 đến 10 (tối đa 213 byte), tự chọn mặt nạ theo điểm phạt.
 * Thuật toán theo ISO/IEC 18004; cách tổ chức tham khảo "QR Code generator library" của Project Nayuki.
 *
 * MIT License
 * Copyright (c) Project Nayuki (https://www.nayuki.io/page/qr-code-generator-library)
 * Copyright (c) 2026 TruyenCloud
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */
(function (root) {
  "use strict";

  // Mức M, chỉ số = phiên bản (0 bỏ trống): số mã sửa lỗi mỗi khối và số khối.
  var ECC_PER_BLOCK = [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
  var NUM_BLOCKS = [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
  var MAX_VERSION = 10;

  function rawModules(ver) {
    var r = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
      var n = Math.floor(ver / 7) + 2;
      r -= (25 * n - 10) * n - 55;
      if (ver >= 7) r -= 36;
    }
    return r;
  }

  function dataCodewords(ver) {
    return Math.floor(rawModules(ver) / 8) - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
  }

  // Nhân trong GF(2^8) với đa thức rút gọn 0x11D.
  function gfMul(x, y) {
    var z = 0;
    for (var i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11d);
      z ^= ((y >>> i) & 1) * x;
    }
    return z;
  }

  function rsDivisor(degree) {
    var r = [];
    for (var i = 0; i < degree - 1; i++) r.push(0);
    r.push(1);
    var rootVal = 1;
    for (i = 0; i < degree; i++) {
      for (var j = 0; j < r.length; j++) {
        r[j] = gfMul(r[j], rootVal);
        if (j + 1 < r.length) r[j] ^= r[j + 1];
      }
      rootVal = gfMul(rootVal, 0x02);
    }
    return r;
  }

  function rsRemainder(data, divisor) {
    var r = divisor.map(function () { return 0; });
    data.forEach(function (b) {
      var factor = b ^ r.shift();
      r.push(0);
      divisor.forEach(function (coef, i) { r[i] ^= gfMul(coef, factor); });
    });
    return r;
  }

  function maskBit(k, x, y) {
    switch (k) {
      case 0: return (x + y) % 2 === 0;
      case 1: return y % 2 === 0;
      case 2: return x % 3 === 0;
      case 3: return (x + y) % 3 === 0;
      case 4: return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
      case 5: return (x * y) % 2 + (x * y) % 3 === 0;
      case 6: return ((x * y) % 2 + (x * y) % 3) % 2 === 0;
      default: return ((x + y) % 2 + (x * y) % 3) % 2 === 0;
    }
  }

  /** Trả về ma trận boolean (true = ô tối), hoặc null nếu chuỗi quá dài. */
  function encode(text) {
    var bytes = Array.from(new TextEncoder().encode(String(text)));
    var ver, capacity;
    for (ver = 1; ver <= MAX_VERSION; ver++) {
      capacity = dataCodewords(ver) * 8;
      if (4 + (ver < 10 ? 8 : 16) + bytes.length * 8 <= capacity) break;
    }
    if (ver > MAX_VERSION) return null;

    // 1. Chuỗi bit dữ liệu: chế độ byte, độ dài, dữ liệu, kết thúc, đệm.
    var bits = [];
    function put(val, len) { for (var i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); }
    put(0x4, 4);
    put(bytes.length, ver < 10 ? 8 : 16);
    bytes.forEach(function (b) { put(b, 8); });
    put(0, Math.min(4, capacity - bits.length));
    put(0, (8 - (bits.length % 8)) % 8);
    for (var pad = 0xec; bits.length < capacity; pad ^= 0xec ^ 0x11) put(pad, 8);
    var data = [];
    for (var i = 0; i < bits.length; i += 8) {
      var byte = 0;
      for (var b = 0; b < 8; b++) byte = (byte << 1) | bits[i + b];
      data.push(byte);
    }

    // 2. Chia khối, thêm mã sửa lỗi Reed-Solomon, xen kẽ.
    var numBlocks = NUM_BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver];
    var rawCodewords = Math.floor(rawModules(ver) / 8);
    var numShort = numBlocks - (rawCodewords % numBlocks);
    var shortLen = Math.floor(rawCodewords / numBlocks);
    var divisor = rsDivisor(eccLen), blocks = [], k = 0;
    for (i = 0; i < numBlocks; i++) {
      var dat = data.slice(k, k + shortLen - eccLen + (i < numShort ? 0 : 1));
      k += dat.length;
      var ecc = rsRemainder(dat, divisor);
      if (i < numShort) dat.push(0);
      blocks.push(dat.concat(ecc));
    }
    var codewords = [];
    for (i = 0; i < blocks[0].length; i++) {
      for (var j = 0; j < numBlocks; j++) {
        if (i !== shortLen - eccLen || j >= numShort) codewords.push(blocks[j][i]);
      }
    }

    // 3. Ma trận và các mẫu cố định.
    var size = ver * 4 + 17, mod = [], fn = [];
    for (i = 0; i < size; i++) {
      mod.push(new Array(size).fill(false));
      fn.push(new Array(size).fill(false));
    }
    function set(x, y, dark) { mod[y][x] = dark; fn[y][x] = true; }

    for (i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    function finder(cx, cy) {
      for (var dy = -4; dy <= 4; dy++) {
        for (var dx = -4; dx <= 4; dx++) {
          var x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
          if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d !== 2 && d !== 4);
        }
      }
    }
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);

    var align = [];
    if (ver > 1) {
      var na = Math.floor(ver / 7) + 2;
      var step = Math.floor((ver * 8 + na * 3 + 5) / (na * 4 - 4)) * 2;
      for (var pos = size - 7; align.length < na - 1; pos -= step) align.unshift(pos);
      align.unshift(6);
    }
    for (i = 0; i < align.length; i++) {
      for (j = 0; j < align.length; j++) {
        var last = align.length - 1;
        if ((i === 0 && j === 0) || (i === 0 && j === last) || (i === last && j === 0)) continue;
        for (var ay = -2; ay <= 2; ay++) {
          for (var ax = -2; ax <= 2; ax++) set(align[i] + ax, align[j] + ay, Math.max(Math.abs(ax), Math.abs(ay)) !== 1);
        }
      }
    }

    function drawFormat(mask) {
      var fdata = mask; // mức M = 00
      var rem = fdata;
      for (var t = 0; t < 10; t++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
      var fbits = ((fdata << 10) | rem) ^ 0x5412;
      function g(n) { return ((fbits >>> n) & 1) !== 0; }
      for (t = 0; t <= 5; t++) set(8, t, g(t));
      set(8, 7, g(6)); set(8, 8, g(7)); set(7, 8, g(8));
      for (t = 9; t < 15; t++) set(14 - t, 8, g(t));
      for (t = 0; t < 8; t++) set(size - 1 - t, 8, g(t));
      for (t = 8; t < 15; t++) set(8, size - 15 + t, g(t));
      set(8, size - 8, true);
    }
    drawFormat(0);

    if (ver >= 7) {
      var vrem = ver;
      for (i = 0; i < 12; i++) vrem = (vrem << 1) ^ ((vrem >>> 11) * 0x1f25);
      var vbits = (ver << 12) | vrem;
      for (i = 0; i < 18; i++) {
        var dark = ((vbits >>> i) & 1) !== 0, va = size - 11 + (i % 3), vb = Math.floor(i / 3);
        set(va, vb, dark); set(vb, va, dark);
      }
    }

    // 4. Đặt dữ liệu theo đường zigzag.
    var bitIndex = 0, totalBits = codewords.length * 8;
    for (var right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (var vert = 0; vert < size; vert++) {
        for (j = 0; j < 2; j++) {
          var x = right - j, upward = ((right + 1) & 2) === 0, y = upward ? size - 1 - vert : vert;
          if (!fn[y][x] && bitIndex < totalBits) {
            mod[y][x] = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) !== 0;
            bitIndex++;
          }
        }
      }
    }

    // 5. Chọn mặt nạ có điểm phạt thấp nhất.
    function applyMask(mk) {
      for (var yy = 0; yy < size; yy++) {
        for (var xx = 0; xx < size; xx++) if (!fn[yy][xx] && maskBit(mk, xx, yy)) mod[yy][xx] = !mod[yy][xx];
      }
    }
    function cell(line, idx, horiz) {
      if (idx < 0 || idx >= size) return false;
      return horiz ? mod[line][idx] : mod[idx][line];
    }
    function penalty() {
      var score = 0, dark = 0, line, idx, run, h;
      var finderLike = [true, false, true, true, true, false, true];
      for (h = 0; h < 2; h++) {
        var horiz = h === 0;
        for (line = 0; line < size; line++) {
          run = 1;
          for (idx = 1; idx < size; idx++) {
            if (cell(line, idx, horiz) === cell(line, idx - 1, horiz)) {
              run++;
              if (run === 5) score += 3; else if (run > 5) score++;
            } else run = 1;
          }
          for (idx = 0; idx + 7 <= size; idx++) {
            var match = true;
            for (var p = 0; p < 7 && match; p++) if (cell(line, idx + p, horiz) !== finderLike[p]) match = false;
            if (!match) continue;
            var before = true, after = true;
            for (p = 1; p <= 4; p++) {
              if (cell(line, idx - p, horiz)) before = false;
              if (cell(line, idx + 6 + p, horiz)) after = false;
            }
            if (before || after) score += 40;
          }
        }
      }
      for (var yy = 0; yy < size; yy++) {
        for (var xx = 0; xx < size; xx++) {
          if (mod[yy][xx]) dark++;
          if (yy < size - 1 && xx < size - 1) {
            var c = mod[yy][xx];
            if (c === mod[yy][xx + 1] && c === mod[yy + 1][xx] && c === mod[yy + 1][xx + 1]) score += 3;
          }
        }
      }
      var total = size * size;
      score += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
      return score;
    }
    var best = 0, bestScore = Infinity;
    for (var mk = 0; mk < 8; mk++) {
      applyMask(mk);
      drawFormat(mk);
      var sc = penalty();
      if (sc < bestScore) { bestScore = sc; best = mk; }
      applyMask(mk); // XOR lần nữa để trả lại như cũ
    }
    applyMask(best);
    drawFormat(best);
    return mod;
  }

  /** Vẽ mã QR thành <svg> (ô tối màu đen trên nền trắng, có lề tĩnh 4 ô). */
  function svg(text, label) {
    var m = encode(text);
    if (!m) return null;
    var n = m.length, q = 4, d = "";
    for (var y = 0; y < n; y++) {
      for (var x = 0; x < n; x++) if (m[y][x]) d += "M" + (x + q) + " " + (y + q) + "h1v1h-1z";
    }
    var ns = "http://www.w3.org/2000/svg";
    var el = document.createElementNS(ns, "svg");
    el.setAttribute("viewBox", "0 0 " + (n + q * 2) + " " + (n + q * 2));
    el.setAttribute("shape-rendering", "crispEdges");
    el.setAttribute("role", "img");
    if (label) el.setAttribute("aria-label", label);
    var bg = document.createElementNS(ns, "rect");
    bg.setAttribute("width", "100%");
    bg.setAttribute("height", "100%");
    bg.setAttribute("fill", "#fff");
    var path = document.createElementNS(ns, "path");
    path.setAttribute("d", d);
    path.setAttribute("fill", "#000");
    el.appendChild(bg);
    el.appendChild(path);
    return el;
  }

  root.TruyenCloudQR = { encode: encode, svg: svg };
})(typeof window !== "undefined" ? window : globalThis);
