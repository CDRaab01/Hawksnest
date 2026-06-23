import type { ComponentType } from "react";
import type { HassEntity } from "../lib/ha";
import type { OverrideMap } from "../lib/resolve";

export type Density = "comfortable" | "compact";

export interface CardProps {
  entity: HassEntity;
  overrides: OverrideMap;
  density?: Density;
}

export type CardComponent = ComponentType<CardProps>;
