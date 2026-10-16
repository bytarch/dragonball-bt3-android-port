/**
 * The web file layer for port/tools/web.py: the disc image over HTTP, in place of the data folder
 * the other builds read with fopen (port/src/plat_fcache.c, plat_file.c).
 *
 * The page names the image: Module.dataUrl (default gamedata.iso next to the page; ?data=URL overrides) —
 * an ISO9660 file. Paths the C side already computes top down ("DATA/PZS3US1.AFS") resolve here once per
 * path in a directory cache; reads are HTTP Range requests against the CDN; small files (up to 8 MiB) get
 * cached whole so a re-read costs nothing. AFS spans stay the C side's business (plat_fcache.c), as on the
 * other builds: it opens the archive with these primitives and reads into spans with its own table.
 */

var WEB_DATA;
var WEB_DATA__deps = [];
var WEB_DATA__postset = '';
WEB_DATA = {
  url: null,
  size: 0, /* the image's size in bytes (from a ranged GET) */
  resolved: {}, /* path -> {lba, size} */
  dirs: {}, /* directory lba -> parsed Map(name -> {lba, size, dir}) */
  small: {}, /* path -> Uint8Array, files kept whole */
  handles: {}, /* id -> {lba, size} */
  next: 0,

  async init() {
    if (this.url !== null) {
      return this.url;
    }
    var q = location.search.match(/[?&]data=([^&]+)/);
    this.url = (typeof Module !== 'undefined' && Module.dataUrl) ? Module.dataUrl : 'gamedata.iso';
    if (q !== null) {
      this.url = decodeURIComponent(q[1]);
    }
    return this.url;
  },

  get(u, a, b) {
    return fetch(u, {headers: {Range: 'bytes=' + a + '-' + b}}).then(function (r) {
      if (!r.ok && r.status !== 206) {
        throw new Error('http ' + r.status + ' for ' + u);
      }
      return r.arrayBuffer();
    });
  },

  /* The ISO9660 root descriptor: the root directory's record is 34+ bytes in at 32768+156. */
  async root() {
    if (this.rootDir !== undefined) {
      return this.rootDir;
    }
    var buf = await this.get(await this.init(), 32768, 34815);
    var d = new DataView(buf);
    if (buf.byteLength < 2048 || String.fromCharCode(d.getUint8(1), d.getUint8(2), d.getUint8(3), d.getUint8(4), d.getUint8(5)) !== 'CD001') {
      throw new Error('not an ISO9660 image');
    }
    var p = 156; /* the root record, from the PVD's start */
    this.rootDir = {lba: d.getUint32(p + 2, true), size: d.getUint32(p + 10, true)};
    return this.rootDir;
  },

  /* Cache the directory entries of one ISO9660 extent: name -> {lba, size}. */
  async entries(node) {
    var key = node.lba;
    if (this.dirs[key] !== undefined) {
      return this.dirs[key];
    }
    var u8 = new Uint8Array(await this.get(await this.init(), node.lba * 2048, node.lba * 2048 + node.size - 1));
    var out = {};
    var pos = 0;
    while (pos + 34 <= u8.length) {
      var len = u8[pos];
      if (len === 0) {
        /* a record never crosses a sector: skip the padding to the next one */
        pos = (pos | 2047) + 1;
        continue;
      }
      var name = '', k;
      for (k = 0; k < u8[pos + 32]; k++) {
        name += String.fromCharCode(u8[pos + 33 + k]);
      }
      var i = name.indexOf(';');
      if (i > 0) {
        name = name.substring(0, i);
      }
      if (u8[pos + 25] & 2) {
        out[name.toUpperCase()] = {lba: (u8[pos + 2] | u8[pos + 3] << 8 | u8[pos + 4] << 16 | u8[pos + 5] << 24) >>> 0,
                                   size: (u8[pos + 10] | u8[pos + 11] << 8 | u8[pos + 12] << 16 | u8[pos + 13] << 24) >>> 0,
                                   dir: true};
      } else {
        out[name.toUpperCase()] = {lba: (u8[pos + 2] | u8[pos + 3] << 8 | u8[pos + 4] << 16 | u8[pos + 5] << 24) >>> 0,
                                   size: (u8[pos + 10] | u8[pos + 11] << 8 | u8[pos + 12] << 16 | u8[pos + 13] << 24) >>> 0};
      }
      pos += len;
    }
    this.dirs[key] = out;
    return out;
  },

  /* An ISO path top-down ("DATA/PZS3US1.AFS"): the entry record of the file. */
  async lookup(path) {
    if (this.resolved[path] !== undefined) {
      return this.resolved[path];
    }
    var node = await this.root();
    var seg = path.toUpperCase().split('/');
    for (var k = 0; k < seg.length; k++) {
      var dir = await this.entries(node);
      node = dir[seg[k].toUpperCase()];
      if (node === undefined || k === seg.length - 1 && node.dir) {
        throw new Error('not in the image: ' + path);
      }
    }
    this.resolved[path] = node;
    return node;
  },

  /* C-facing primitives (EM_ASYNC_JS wrappers below): the page calls fetch with the image's own byte offsets. */
  async open(path) {
    var node = await this.lookup(path);
    this.handles[++this.next] = node;
    return this.next;
  },

  sizeOf(h) {
    return this.handles[h] !== undefined ? this.handles[h].size : -1;
  },

  async read(h, off, len, outPtr) {
    var f = this.handles[h];
    if (f === undefined || off < 0 || off >= f.size || len <= 0 || outPtr < 0) {
      return 0;
    }
    if ((len = Math.min(len, f.size - off)) === 0) {
      return 0;
    }
    var data, key = f.lba;
    if (f.size <= 8 * 1024 * 1024) { /* a handful of MB: the whole file once, then from memory */
      data = this.small[key];
      if (data === undefined) {
        data = new Uint8Array(await this.get(await this.init(), f.lba * 2048, f.lba * 2048 + f.size - 1));
        this.small[key] = data;
      }
      var src = data.subarray(off, off + len);
      HEAPU8.set(src, outPtr);
      return len;
    }
    data = new Uint8Array(await this.get(await this.init(), f.lba * 2048 + off, f.lba * 2048 + off + len - 1));
    HEAPU8.set(data, outPtr);
    return len;
  },

  close(h) {
    delete this.handles[h];
  }
};

var LibraryWebDataBridge = {
  /* One handle per archive or file the C side asks for; size comes back with it (the game sizes buffers by it). */
  WebData_Open__async: true,
  WebData_Open: async function (path) { return await WEB_DATA.open(UTF8ToString(path)); },

  WebData_Size: function (h) { return WEB_DATA.sizeOf(h); },

  WebData_Read__async: true,
  WebData_Read: async function (h, off, bytes, buf) { return await WEB_DATA.read(h, off, bytes, buf); },

  WebData_Close: function (h) { WEB_DATA.close(h); },
};

addToLibrary(LibraryWebDataBridge);
