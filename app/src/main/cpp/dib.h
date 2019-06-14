/*
	DIB (Device Independent Bitmap) interface

	/Mic, 2009
*/

#ifndef __DIB_H__
#define __DIB_H__

#include <stdlib.h>
#include <stdio.h>


class DIB {
public:
    int width, height, bitCount, errorCode, pitch;
    bool allocatedMemory;
    unsigned char *bits;
    unsigned char *palette;

    DIB() {
        bits = palette = NULL;
        width = height = bitCount = 0;
        allocatedMemory = false;
    }

    DIB(unsigned char *diBits, int diWidth, int diHeight, int diBitCount, unsigned char *diPal) {
        bits = diBits;
        width = diWidth;
        height = diHeight;
        bitCount = diBitCount;
        pitch = width * bitCount >> 3;
        palette = diPal;
        allocatedMemory = false;
    }


    DIB(int diWidth, int diHeight, int diBitCount) {
        bits = (unsigned char *) malloc((size_t) (diWidth * diHeight * (diBitCount >> 3)));
        palette = NULL;
        width = diWidth;
        height = diHeight;
        bitCount = diBitCount;
        pitch = width * bitCount >> 3;
        allocatedMemory = true;
    }


    ~DIB() {
        if (allocatedMemory)
            free(bits);
    }

    void saveBMP(char *fileName, bool flip);
};

#endif
