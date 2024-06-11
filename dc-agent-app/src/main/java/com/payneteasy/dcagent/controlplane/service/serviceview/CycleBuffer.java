package com.payneteasy.dcagent.controlplane.service.serviceview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CycleBuffer<T> {

    private final T[] buffer;
    private final int size;
    private       int position = 0;

    public CycleBuffer(T[] buffer) {
        this.buffer = buffer;
        size        = buffer.length;
    }

    public void add(T aElement) {
        buffer[position] = aElement;
        position++;
        if (position >= size) {
            position = 0;
        }
    }

    public List<T> toList() {
        List<T> list = new ArrayList<>();
        for (int i = position; i < size; i++) {
            T element = buffer[i];
            if (element != null) {
                list.add(element);
            }
        }

        list.addAll(Arrays.asList(buffer).subList(0, position));

        return list;
    }

}
