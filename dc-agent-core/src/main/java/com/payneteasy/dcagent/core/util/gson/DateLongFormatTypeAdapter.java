package com.payneteasy.dcagent.core.util.gson;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Date;

public class DateLongFormatTypeAdapter extends TypeAdapter<Date> {

    @Override
    public void write(JsonWriter aOut, Date aDate) throws IOException {
        if(aDate != null) {
            aOut.value(aDate.getTime());
        } else {
            aOut.nullValue();
        }
    }

    @Override
    public Date read(JsonReader aIn) throws IOException {
        return new Date(aIn.nextLong());
    }

}