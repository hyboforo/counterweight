import { useEffect, useRef } from "react";

/**
 * Recognises a barcode scanner and routes what it typed to one place.
 *
 * The bill of materials specifies a **USB HID keyboard-wedge** scanner: no
 * driver, no agent involvement, and it types into whatever element has focus.
 * That last part is the entire problem. The classic till bug is a scan landing
 * in a quantity box or a payment amount because the caret happened to be there,
 * and the result is a sale for 6001234500011 bags of cement.
 *
 * A wedge can be told apart from a person by **timing**. It emits characters a
 * few milliseconds apart and finishes with Enter; nobody types thirteen digits
 * with under 30 ms between each. So keystrokes are watched at the document,
 * a burst that arrives fast enough and ends in Enter is treated as a scan, and
 * it is delivered to [onScan] regardless of focus.
 *
 * Two details that matter:
 *
 *  - The buffer resets after a human-length pause, so ordinary typing never
 *    accumulates into a phantom scan.
 *  - [enabled] exists so a scan cannot fire behind an overlay. A barcode read
 *    while the payment panel is open must not add a line nobody can see.
 */

const MAX_GAP_MS = 30;
const MIN_LENGTH = 8;

export function useScanner(
  onScan: (code: string) => void,
  enabled: boolean,
) {
  const buffer = useRef("");
  const lastAt = useRef(0);
  const fastEnough = useRef(true);
  const handler = useRef(onScan);
  const isEnabled = useRef(enabled);

  useEffect(() => {
    handler.current = onScan;
  }, [onScan]);
  useEffect(() => {
    isEnabled.current = enabled;
  }, [enabled]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      const now = performance.now();
      const gap = now - lastAt.current;

      if (e.key === "Enter") {
        const code = buffer.current;
        const wasScan = fastEnough.current && code.length >= MIN_LENGTH;
        buffer.current = "";
        fastEnough.current = true;

        if (wasScan && isEnabled.current) {
          // Stop the Enter as well — it would otherwise submit whatever form
          // the caret is sitting in.
          e.preventDefault();
          e.stopPropagation();
          handler.current(code);
        }
        return;
      }

      // Only single printable characters can be part of a barcode.
      if (e.key.length !== 1) return;

      if (gap > 120) {
        // A human-length pause. Start again.
        buffer.current = "";
        fastEnough.current = true;
      } else if (gap > MAX_GAP_MS) {
        // Too slow to be a wedge. Whatever this is, it is being typed.
        fastEnough.current = false;
      }

      buffer.current += e.key;
      lastAt.current = now;
    }

    // Capture phase: the scan has to be intercepted before an input's own
    // handlers see it, or the digits land in the field anyway.
    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, []);
}
