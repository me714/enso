//! # Counter
//!
//! Counter type. Uses an internal global value to ensure every instance created has a different
//! value.

use crate::prelude::*;



// ===============
// === Counter ===
// ===============

/// Implements a globally-unique counter.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Ord, PartialOrd)]
pub struct Counter {
    value: std::num::NonZeroU64,
}

impl Counter {
    /// Generate a unique value.
    #[allow(clippy::new_without_default)] // Every new instance must have a different value.
    #[allow(unsafe_code)] // See comment inside.
    pub fn new() -> Self {
        use std::sync::atomic;
        static NEXT: atomic::AtomicU64 = atomic::AtomicU64::new(1);
        let value = NEXT.fetch_add(1, atomic::Ordering::Relaxed);
        // The counter is 64-bit. If we were to increment it 100 billion times per second,
        // it would take 5,845 years to wrap.
        let value = if cfg!(debug_assertions) {
            std::num::NonZeroU64::new(value).unwrap()
        } else {
            unsafe { std::num::NonZeroU64::new_unchecked(value) }
        };
        Self { value }
    }
}

impl From<Counter> for u64 {
    fn from(Counter { value }: Counter) -> Self {
        value.into()
    }
}

impl CloneRef for Counter {
    fn clone_ref(&self) -> Self {
        Self { value: self.value }
    }
}



// =================
// === define_id ===
// =================

/// Define a type usable as an ID, with unique values generated by a counter.
///
/// Example usage:
/// ```
/// enso_data_structures::define_id! {
///     /// Example ID type. This macro syntax allows applying doc comments and attribute macros to
///     /// the type being defined.
///     pub struct MyId($);
/// }
///
/// // `new()` produces unique values of the new type.
/// let a = MyId::new();
/// let b = MyId::new();
/// assert_ne!(a, b);
/// ```
#[macro_export]
macro_rules! define_id {
    (
        $(#[$attrs:meta])*
        pub struct $name:ident($);
    ) => {
        // FIXME[anyone]: ID types should not be nullable. I [KW] am implementing it this way here
        //  to maintain compatibility with the previous implementations of IDs.
        //  See: https://www.pivotaltracker.com/story/show/181626362
        $(#[$attrs])*
        #[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq, Ord, PartialOrd)]
        pub struct $name(Option<$crate::counter::Counter>);

        impl $name {
            /// Create a new unique ID.
            pub fn new() -> Self {
                Self(Some($crate::counter::Counter::new()))
            }
        }

        impl std::fmt::Display for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                std::fmt::Debug::fmt(self, f)
            }
        }

        /// Convert to a raw count, losing counter-type information.
        impl From<$name> for Option<u64> {
            fn from(counter: $name) -> Self {
                counter.0.map(|value| value.into())
            }
        }

        impl $crate::prelude::CloneRef for $name {
            fn clone_ref(&self) -> Self {
                Self(self.0)
            }
        }
    };
}



// =============
// === Tests ===
// =============

#[cfg(test)]
mod tests {
    define_id! { pub struct TestIdA($); }
    define_id! { pub struct TestIdB($); }

    #[test]
    fn test_counter() {
        let a0 = TestIdA::new();
        let a1 = TestIdA::new();
        assert_ne!(a0, a1);

        let b0 = TestIdB::new();
        let b1 = TestIdB::new();
        assert_ne!(b0, b1);
    }
}
