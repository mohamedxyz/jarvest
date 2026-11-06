//! Password-protected backups. The backup file is encrypted as a whole with XChaCha20-Poly1305, using a
//! key made from the password by Argon2id. Both come from the RustCrypto crates: nothing here invents
//! its own cryptography.
//!
//! File layout (all numbers big-endian):
//!
//! | bytes | what |
//! |---|---|
//! | 0-3 | the magic text `JVBK` |
//! | 4 | format version (1) |
//! | 5-8, 9-12, 13-16 | Argon2 memory (KiB), passes, lanes |
//! | 17-32 | salt |
//! | 33-56 | nonce |
//! | 57- | ciphertext and its 16-byte tag |
//!
//! The whole header is authenticated as associated data, so changing any byte of it, or of the
//! ciphertext, makes decryption fail. A backup is untrusted input, so the Argon2 settings read from the
//! file are checked against limits before they are used: a huge value would otherwise freeze the phone.

use std::fs;

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, KeyInit, OsRng, Payload, rand_core::RngCore};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};

use crate::CoreError;

const MAGIC: &[u8; 4] = b"JVBK";
const FORMAT_VERSION: u8 = 1;
const HEADER_LEN: usize = 57;
const TAG_LEN: usize = 16;
/// Largest encrypted file that is read into memory.
const MAX_FILE_BYTES: u64 = 256 * 1024 * 1024;
const MIN_PASSWORD_CHARS: usize = 8;

/// What the app uses to protect a backup: 32 MiB of memory and 3 passes, which takes well under a second
/// on a current phone.
pub const DEFAULT_KDF: Kdf = Kdf {
    memory_kib: 32 * 1024,
    passes: 3,
    lanes: 1,
};

// Limits for settings read from a file.
const MEMORY_RANGE: std::ops::RangeInclusive<u32> = 8..=64 * 1024;
const PASSES_RANGE: std::ops::RangeInclusive<u32> = 1..=6;
const LANES_RANGE: std::ops::RangeInclusive<u32> = 1..=4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Kdf {
    pub memory_kib: u32,
    pub passes: u32,
    pub lanes: u32,
}

fn bad(reason: &str) -> CoreError {
    CoreError::InvalidBackup {
        reason: reason.to_string(),
    }
}

fn io(e: std::io::Error) -> CoreError {
    CoreError::Database {
        detail: e.to_string(),
    }
}

/// True when the file starts with the magic text of a protected backup.
pub fn is_encrypted(path: &str) -> Result<bool, CoreError> {
    use std::io::Read;
    let mut head = [0u8; 4];
    let mut file = fs::File::open(path).map_err(io)?;
    Ok(file.read_exact(&mut head).is_ok() && &head == MAGIC)
}

fn derive_key(password: &str, salt: &[u8], kdf: Kdf) -> Result<[u8; 32], CoreError> {
    let params = Params::new(kdf.memory_kib, kdf.passes, kdf.lanes, Some(32))
        .map_err(|_| bad("this backup uses settings this app does not accept"))?;
    let mut key = [0u8; 32];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password.as_bytes(), salt, &mut key)
        .map_err(|_| bad("this backup uses settings this app does not accept"))?;
    Ok(key)
}

/// Encrypts the file at `plain_path` into `dest`, with the given settings.
pub fn encrypt_file(
    plain_path: &str,
    dest: &str,
    password: &str,
    kdf: Kdf,
) -> Result<(), CoreError> {
    if password.chars().count() < MIN_PASSWORD_CHARS {
        return Err(CoreError::Invalid {
            reason: "Use a password of at least 8 characters".into(),
        });
    }
    let plain = fs::read(plain_path).map_err(io)?;
    let mut salt = [0u8; 16];
    let mut nonce = [0u8; 24];
    OsRng.fill_bytes(&mut salt);
    OsRng.fill_bytes(&mut nonce);
    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(MAGIC);
    header.push(FORMAT_VERSION);
    header.extend_from_slice(&kdf.memory_kib.to_be_bytes());
    header.extend_from_slice(&kdf.passes.to_be_bytes());
    header.extend_from_slice(&kdf.lanes.to_be_bytes());
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);
    debug_assert_eq!(header.len(), HEADER_LEN);

    let key = derive_key(password, &salt, kdf)?;
    let cipher = XChaCha20Poly1305::new((&key).into());
    let ciphertext = cipher
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: &plain,
                aad: &header,
            },
        )
        .map_err(|_| CoreError::Database {
            detail: "the backup could not be encrypted".into(),
        })?;
    let mut out = header;
    out.extend_from_slice(&ciphertext);
    // Written to a side file and renamed, so a failure never leaves half a backup behind.
    let temp = format!("{dest}.part");
    fs::write(&temp, out).map_err(io)?;
    fs::rename(&temp, dest).map_err(|e| {
        let _ = fs::remove_file(&temp);
        io(e)
    })
}

/// Decrypts the protected backup at `src` into `out_path`. A wrong password and a damaged file look the
/// same to the cipher, so both give the same message.
pub fn decrypt_file(src: &str, password: &str, out_path: &str) -> Result<(), CoreError> {
    let size = fs::metadata(src).map_err(io)?.len();
    if size > MAX_FILE_BYTES {
        return Err(bad("the file is too large to be a backup"));
    }
    let data = fs::read(src).map_err(io)?;
    if data.len() < 4 || &data[..4] != MAGIC {
        return Err(bad("this isn't a protected backup"));
    }
    if data.len() < HEADER_LEN + TAG_LEN {
        return Err(bad(
            "the file is cut short, so the copy or download probably didn't finish",
        ));
    }
    if data[4] != FORMAT_VERSION {
        return Err(bad(
            "it was made by a newer version of the app, so update Jarvest and try again",
        ));
    }
    let number =
        |at: usize| u32::from_be_bytes([data[at], data[at + 1], data[at + 2], data[at + 3]]);
    let kdf = Kdf {
        memory_kib: number(5),
        passes: number(9),
        lanes: number(13),
    };
    // Checked before use: the file is untrusted and these numbers decide how much memory and time are spent.
    if !MEMORY_RANGE.contains(&kdf.memory_kib)
        || !PASSES_RANGE.contains(&kdf.passes)
        || !LANES_RANGE.contains(&kdf.lanes)
    {
        return Err(bad("this backup uses settings this app does not accept"));
    }
    if password.is_empty() {
        return Err(bad("wrong password or damaged file"));
    }
    let (header, ciphertext) = data.split_at(HEADER_LEN);
    let key = derive_key(password, &header[17..33], kdf)?;
    let cipher = XChaCha20Poly1305::new((&key).into());
    let plain = cipher
        .decrypt(
            XNonce::from_slice(&header[33..57]),
            Payload {
                msg: ciphertext,
                aad: header,
            },
        )
        .map_err(|_| bad("wrong password or damaged file"))?;
    fs::write(out_path, plain).map_err(io)
}
