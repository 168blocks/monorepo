//! Reactor-style ABI for running egglog inside a Wasm host (Endive).
//!
//! Host protocol:
//!   ptr = egglog_alloc(len)            -> write UTF-8 program bytes at ptr
//!   rc  = egglog_run(ptr, len)         -> 0 ok, 1 error; frees the input
//!   rc  = egglog_session_run(ptr, len) -> same, on the persistent session e-graph
//!   out = egglog_out_ptr(), n = egglog_out_len() -> UTF-8 outputs or error message of the last run
//!
//! `egglog_run` uses a fresh e-graph per call. The session e-graph outlives calls: load datatypes and
//! rules once, then per query `(push) ... (extract q) (pop)`. `egglog_session_reset` drops it.
use egglog::EGraph;
use std::cell::RefCell;

thread_local! {
    static OUT: RefCell<Vec<u8>> = RefCell::new(Vec::new());
    static SESSION: RefCell<Option<EGraph>> = RefCell::new(None);
}

#[unsafe(no_mangle)]
pub extern "C" fn egglog_alloc(len: usize) -> *mut u8 {
    let mut v = Vec::<u8>::with_capacity(len);
    let p = v.as_mut_ptr();
    std::mem::forget(v);
    p
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn egglog_free(ptr: *mut u8, len: usize) {
    unsafe { drop(Vec::from_raw_parts(ptr, 0, len)) };
}

fn run_on(eg: &mut EGraph, src: &str) -> Result<String, String> {
    let outputs = eg.parse_and_run_program(None, src).map_err(|e| e.to_string())?;
    Ok(outputs.iter().map(|o| o.to_string()).collect::<Vec<_>>().join(""))
}

fn run_program(src: &str) -> Result<String, String> {
    run_on(&mut EGraph::default(), src)
}

fn session_run(src: &str) -> Result<String, String> {
    SESSION.with(|s| run_on(s.borrow_mut().get_or_insert_with(EGraph::default), src))
}

unsafe fn run_with(ptr: *mut u8, len: usize, f: fn(&str) -> Result<String, String>) -> i32 {
    let bytes = unsafe { Vec::from_raw_parts(ptr, len, len) };
    let (rc, out) = match std::str::from_utf8(&bytes) {
        Err(e) => (1, format!("invalid utf-8: {e}")),
        Ok(src) => match f(src) {
            Ok(s) => (0, s),
            Err(e) => (1, e),
        },
    };
    OUT.with(|o| *o.borrow_mut() = out.into_bytes());
    rc
}

/// Run a self-contained program on a fresh e-graph.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn egglog_run(ptr: *mut u8, len: usize) -> i32 {
    unsafe { run_with(ptr, len, run_program) }
}

/// Run a program on the persistent session e-graph (created on first use).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn egglog_session_run(ptr: *mut u8, len: usize) -> i32 {
    unsafe { run_with(ptr, len, session_run) }
}

/// Drop the session e-graph.
#[unsafe(no_mangle)]
pub extern "C" fn egglog_session_reset() {
    SESSION.with(|s| *s.borrow_mut() = None);
}

#[unsafe(no_mangle)]
pub extern "C" fn egglog_out_ptr() -> *const u8 {
    OUT.with(|o| o.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn egglog_out_len() -> usize {
    OUT.with(|o| o.borrow().len())
}
