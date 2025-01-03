import type {
  BarSeriesOption,
  CustomSeriesOption,
  LineSeriesOption,
  ScatterSeriesOption,
} from "echarts/charts";
import type { ElementEvent } from "echarts/core";
import type { BrushAreaParam } from "echarts/types/src/component/brush/BrushModel";
import type { ZRRawMouseEvent } from "zrender/lib/core/types";

export type EChartsSeriesMouseEvent<TDatum = unknown> = {
  event: ElementEvent["event"] & {
    event: ZRRawMouseEvent;
  };
  dataIndex?: number;
  seriesId?: string;
  name?: string;
  value: any;
  dataType?: string;
  seriesType: string;
  data: TDatum;
};

export type EChartsSeriesBrushEndEvent = EChartsSeriesMouseEvent & {
  areas: BrushAreaParam[];
};

export type EChartsCartesianCoordinateSystem = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type WaterfallSeriesOption =
  | CustomSeriesOption
  | ScatterSeriesOption
  | BarSeriesOption
  | LineSeriesOption;

export type ShowWarning = (warning: string) => void;
