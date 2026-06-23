import type { ReactNode } from "react";

type Size = "sm" | "md" | "lg" | "xl";

const SIZE: Record<Size, string> = {
  sm: "text-data-sm",
  md: "text-data-md",
  lg: "text-data-lg",
  xl: "text-data-xl",
};

interface DataTextProps {
  children: ReactNode;
  size?: Size;
  className?: string;
}

/** Mono numeral readout (JetBrains Mono, slashed zeros). Data is its own voice. */
export function DataText({ children, size = "sm", className = "" }: DataTextProps) {
  return (
    <span className={["font-mono font-semibold", SIZE[size], className].join(" ")}>
      {children}
    </span>
  );
}
