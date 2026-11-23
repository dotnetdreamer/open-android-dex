/* tslint:disable */
/* eslint-disable */
/**
 * The `ReadableStreamType` enum.
 *
 * *This API requires the following crate features to be activated: `ReadableStreamType`*
 */

export type ReadableStreamType = "bytes";

/**
 * This browser, as the phone knows it.
 */
export class Browser {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    id(): string;
    /**
     * The browser's endpoint: `secret` is the 64 hex characters the page
     * kept from before, or "" for a browser that has none yet.
     */
    static open(secret: string): Promise<Browser>;
    /**
     * Pair with the code the phone shows (Remote access → Add a computer):
     * answers `{"id":…,"name":…}`, the phone to open from now on.
     */
    pair(code: string, name: string): Promise<string>;
    /**
     * The phone's screen: its tunnel's `phone` op, then PhoneStream's bytes
     * both ways ([`PhoneLink`]).
     */
    phone(phone: string): Promise<PhoneLink>;
    /**
     * The phone's screen straight to this browser, by WebRTC: this opens the
     * phone's `phone-rtc` op and gives the page the stream its offer, the
     * phone's answer and both ends' candidates go over ([`RtcLink`]). The
     * page makes the RTCPeerConnection; the media never crosses this link.
     */
    rtc(phone: string): Promise<RtcLink>;
    /**
     * What the page keeps, to be this browser again next time.
     */
    secret(): string;
}

export class IntoUnderlyingByteSource {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    cancel(): void;
    pull(controller: ReadableByteStreamController): Promise<any>;
    start(controller: ReadableByteStreamController): void;
    readonly autoAllocateChunkSize: number;
    readonly type: ReadableStreamType;
}

export class IntoUnderlyingSink {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    abort(reason: any): Promise<any>;
    close(): Promise<any>;
    write(chunk: any): Promise<any>;
}

export class IntoUnderlyingSource {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    cancel(): void;
    pull(controller: ReadableStreamDefaultController): Promise<any>;
}

/**
 * One open screen: bytes to the phone and from it.
 */
export class PhoneLink {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    close(): void;
    /**
     * The next bytes from the phone, or null when it closed.
     */
    read(): Promise<any>;
    /**
     * Bytes to the phone, in order.
     */
    write(bytes: Uint8Array): Promise<any>;
}

/**
 * The signalling of one WebRTC connection to the phone: [`proto::RtcSignal`]
 * frames, as JSON text, both ways.
 */
export class RtcLink {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    close(): void;
    /**
     * The phone's next message, as JSON text, or null once it has said all.
     */
    recv(): Promise<any>;
    /**
     * One message to the phone: `{"offer":…}` first, then `{"candidate":…}`.
     */
    send(json: string): Promise<any>;
}

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_browser_free: (a: number, b: number) => void;
    readonly __wbg_phonelink_free: (a: number, b: number) => void;
    readonly browser_id: (a: number) => [number, number];
    readonly browser_open: (a: number, b: number) => any;
    readonly browser_pair: (a: number, b: number, c: number, d: number, e: number) => any;
    readonly browser_phone: (a: number, b: number, c: number) => any;
    readonly browser_rtc: (a: number, b: number, c: number) => any;
    readonly browser_secret: (a: number) => [number, number];
    readonly phonelink_close: (a: number) => void;
    readonly phonelink_read: (a: number) => any;
    readonly phonelink_write: (a: number, b: number, c: number) => any;
    readonly rtclink_recv: (a: number) => any;
    readonly rtclink_send: (a: number, b: number, c: number) => any;
    readonly __wbg_rtclink_free: (a: number, b: number) => void;
    readonly rtclink_close: (a: number) => void;
    readonly __wbg_intounderlyingsource_free: (a: number, b: number) => void;
    readonly intounderlyingsource_cancel: (a: number) => void;
    readonly intounderlyingsource_pull: (a: number, b: any) => any;
    readonly __wbg_intounderlyingsink_free: (a: number, b: number) => void;
    readonly intounderlyingsink_abort: (a: number, b: any) => any;
    readonly intounderlyingsink_close: (a: number) => any;
    readonly intounderlyingsink_write: (a: number, b: any) => any;
    readonly __wbg_intounderlyingbytesource_free: (a: number, b: number) => void;
    readonly intounderlyingbytesource_autoAllocateChunkSize: (a: number) => number;
    readonly intounderlyingbytesource_cancel: (a: number) => void;
    readonly intounderlyingbytesource_pull: (a: number, b: any) => any;
    readonly intounderlyingbytesource_start: (a: number, b: any) => void;
    readonly intounderlyingbytesource_type: (a: number) => number;
    readonly ring_core_0_17_14__bn_mul_mont: (a: number, b: number, c: number, d: number, e: number, f: number) => void;
    readonly wasm_bindgen__convert__closures_____invoke__h1365e4914bd11946: (a: number, b: number, c: any) => [number, number];
    readonly wasm_bindgen__convert__closures_____invoke__h5c5f7f6b3a2fb84a: (a: number, b: number, c: any, d: any) => void;
    readonly wasm_bindgen__convert__closures_____invoke__h6319480a4e674af7: (a: number, b: number, c: any) => void;
    readonly wasm_bindgen__convert__closures_____invoke__hee7c1ea1f961ab59: (a: number, b: number, c: any) => void;
    readonly wasm_bindgen__convert__closures_____invoke__h6879c95db5c6a5c9: (a: number, b: number, c: any) => void;
    readonly wasm_bindgen__convert__closures_____invoke__hae2eea4619039c5f: (a: number, b: number) => void;
    readonly wasm_bindgen__convert__closures_____invoke__h6d1ac84ff526fab9: (a: number, b: number) => void;
    readonly wasm_bindgen__convert__closures_____invoke__h4a73dad88f3fdbfb: (a: number, b: number) => void;
    readonly wasm_bindgen__convert__closures_____invoke__h4ec7fadde4aa726c: (a: number, b: number) => void;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __wbindgen_destroy_closure: (a: number, b: number) => void;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
