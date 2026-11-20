package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class NumberSetting extends Setting<Double> {
    private final double min, max, step;

    public NumberSetting(String name, double value, double min, double max, double step) {
        super(name, value);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    @Override
    public void set(Double v) {
        double snapped = Math.round(v / step) * step;
        value = Math.max(min, Math.min(max, snapped));
    }

    public double getMin() { return min; }
    public double getMax() { return max; }
    public float getFloat() { return value.floatValue(); }
    public int getInt() { return value.intValue(); }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }
    @Override public void fromJson(JsonElement json) { set(json.getAsDouble()); }

    @Override
    public String display() {
        return step >= 1 ? String.valueOf(value.intValue()) : String.format("%.2f", value);
    }
}
