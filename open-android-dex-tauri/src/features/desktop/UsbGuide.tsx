import { useState, type ReactNode } from "react";
import { Segmented } from "../../ui/kit";

/**
 * How to turn on USB debugging, for the phone in the person's hand.
 *
 * The one generic recipe that used to be here was Samsung's ("Software
 * information"), and on a Pixel or a Xiaomi that menu does not exist — which
 * is the moment a non-technical person gives up. So the three makers most
 * people own get their own exact path, and every other phone gets the recipe
 * that works on all of them: search Settings for the item by name.
 */

type Maker = "samsung" | "pixel" | "xiaomi" | "other";

const P = ({ children }: { children: ReactNode }) => <span className="ui-path">{children}</span>;

const GUIDES: Record<Maker, ReactNode[]> = {
  samsung: [
    <>
      Open <P>Settings</P> → <P>About phone</P> → <P>Software information</P>.
    </>,
    <>
      Tap <P>Build number</P> seven times, and enter your PIN if asked. You'll see “Developer mode has been turned on”.
    </>,
    <>
      Go back to <P>Settings</P>, open <P>Developer options</P> at the very bottom, and turn on <P>USB debugging</P>.
    </>,
  ],
  pixel: [
    <>
      Open <P>Settings</P> → <P>About phone</P>.
    </>,
    <>
      Scroll to the bottom and tap <P>Build number</P> seven times. Enter your PIN if asked.
    </>,
    <>
      Go to <P>Settings</P> → <P>System</P> → <P>Developer options</P> and turn on <P>USB debugging</P>.
    </>,
  ],
  xiaomi: [
    <>
      Open <P>Settings</P> → <P>About phone</P>.
    </>,
    <>
      Tap <P>OS version</P> (or <P>MIUI version</P>) seven times.
    </>,
    <>
      Go to <P>Settings</P> → <P>Additional settings</P> → <P>Developer options</P> and turn on <P>USB debugging</P>,{" "}
      <P>USB debugging (Security settings)</P> and <P>Install via USB</P>. The last two need a Xiaomi account signed in.
    </>,
  ],
  other: [
    <>
      Open <P>Settings</P> and search for <P>Build number</P>. Tap it seven times, and enter your PIN if asked.
    </>,
    <>
      Search <P>Settings</P> for <P>USB debugging</P> and turn it on.
    </>,
  ],
};

export function UsbGuide() {
  const [maker, setMaker] = useState<Maker>("samsung");
  return (
    <div className="flex flex-col gap-3.5">
      <Segmented
        label="Your phone's maker"
        role="radiogroup"
        value={maker}
        onChange={setMaker}
        className="w-full"
        options={[
          { value: "samsung", label: "Samsung" },
          { value: "pixel", label: "Pixel" },
          { value: "xiaomi", label: "Xiaomi" },
          { value: "other", label: "Other" },
        ]}
      />
      <ol key={maker} className="howto fade-in">
        {GUIDES[maker].map((step, i) => (
          <li key={i}>{step}</li>
        ))}
      </ol>
      <p className="caption">You only do this once. It can be turned off again in Developer options.</p>
    </div>
  );
}
