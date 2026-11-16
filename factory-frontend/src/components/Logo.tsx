/** Ringed "DF" badge, white on the brand gradient. */
export function Logo({ size = "size-8" }: { size?: string }) {
  return (
    <div className={`flex ${size} items-center justify-center rounded-full border-2 border-white text-sm font-bold text-white`}>
      DF
    </div>
  );
}
