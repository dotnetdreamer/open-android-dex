// Remote desktop's Files: the phone's own storage in this browser, beside the
// phone's screen (phone.html). The door serves it at /pfs/ with the same
// token as the screen (LinuxBrowserServer; LinuxFiles of the phone's kind,
// over the phone's shared storage, /storage/emulated/0). The file manager is
// Linux's Files (files.js): the same lists, transfers, Quick look and keys,
// with the phone's places and words here. Design record: doc/phone-remote.md,
// "Files".
//
// What differs from Linux's: the phone's storage has no Trash (Android's own
// is MediaStore's and asks on the phone), so deleting asks first and is for
// good; Android's own folders and apps' folders under Android stay as they
// are; and a drop on the phone's screen, with Files closed, goes into
// Downloads, as scrcpy's does.

import { createFiles } from './files.js';

/** The phone's places, as the door names them (LinuxFiles.info, PHONE). */
const NAMES = {
  phone: 'Phone', downloads: 'Downloads', camera: 'Camera', pictures: 'Pictures', documents: 'Documents',
  music: 'Music', videos: 'Movies',
};

export const PHONE = {
  base: 'pfs/',
  label: 'The phone’s files',
  home: '/',
  rootLabel: 'Phone',
  rootPlace: 'phone',
  order: ['phone', 'downloads', 'camera', 'pictures', 'documents', 'music', 'videos'],
  names: NAMES,
  placeTitle: (id, path) => (id === 'phone' ? 'All of the phone’s storage' : path),
  dropPlace: 'downloads',
  trash: false,
  desk: { icon: 'phone', label: 'Show the phone’s screen', text: 'Screen' },
  on: 'the phone',
  picker: 'phone-files',
  // A big file in pieces of 32 MB, each its own PUT: through the Cloudflare
  // tunnel a request's body may be no more than 100 MB (Cloudflare's free
  // plan), and the door takes a file piece by piece as it takes a resume.
  putMax: 32 * 1024 * 1024,
  why: {
    denied: 'Android does not allow the change.',
    protected: 'Android’s own folders, and the files of apps, stay unchanged from the browser.',
    name: 'The phone does not allow the name.',
    link: 'A link on the phone is in the way.',
    off: 'Remote desktop is turned off on the phone.',
    noaccess: 'LinuxOnDroid needs access to all files on the phone first.',
    unavailable: 'This version of LinuxOnDroid cannot open the phone’s files from a browser.',
  },
  privateTitle: (here) => `Android keeps “${here}” private`,
  privateText: (here) => `Android does not let LinuxOnDroid look inside “${here}”.`,
  gone: (here) => `“${here}” was moved or deleted on the phone.`,
  noEntry: (here) => `The phone does not let files into ${here} from a browser.`,
  nowhere: (n) => `The link “${n}” leads nowhere on the phone.`,
  slash: 'A name on the phone cannot contain a slash (/).',
  runs: null,
  dropDesk: 'Drop to copy into Downloads on the phone',
  deskTarget: 'Downloads on the phone',
  dropSub: (phone) => `${phone || 'The phone'}. A dropped folder arrives with every file and folder inside.`,
  onDesk: (what) => `${what} in Downloads on the phone now`,
  deleteAsk: (list) => (list.length === 1
    ? { title: `Delete “${list[0].n}”?`, text: `“${list[0].n}” is deleted from the phone, and cannot be brought back.` }
    : { title: `Delete ${list.length} items?`, text: `The ${list.length} items are deleted from the phone, and cannot be brought back.` }),
  states: {
    noaccess: ['LinuxOnDroid needs access to all files', 'On the phone, open LinuxOnDroid › Settings › Remote desktop and choose Allow access to all files. Then choose Try again here.'],
    unavailable: ['The phone’s files are not here', 'This version of LinuxOnDroid cannot open the phone’s files from a browser.'],
    off: ['Remote desktop is off', 'Remote desktop is turned off on the phone.'],
    offline: ['The phone did not answer', 'Check that the phone is on the same Wi-Fi as this computer.'],
  },
  stateElse: ['The phone’s files are not here', 'Something went wrong on the phone.'],
};

/** Files for the phone's own storage; {@code app} as files.js's createFiles takes it. */
export function createPhoneFiles(app) {
  return createFiles(app, PHONE);
}
