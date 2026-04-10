package com.cxalloy.integration.tools;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public final class PdfRenderTool {

    private PdfRenderTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PdfRenderTool <input-pdf> <output-dir>");
            System.exit(1);
        }

        File input = new File(args[0]);
        File outputDir = new File(args[1]);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("Failed to create output directory: " + outputDir);
        }

        try (PDDocument document = PDDocument.load(input)) {
            PDFRenderer renderer = new PDFRenderer(document);
            PDFTextStripper stripper = new PDFTextStripper();
            System.out.println("pages=" + document.getNumberOfPages());
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 150, ImageType.RGB);
                File imageFile = new File(outputDir, "page-" + (page + 1) + ".png");
                ImageIO.write(image, "png", imageFile);

                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                String text = stripper.getText(document).replaceAll("\\s+", " ").trim();
                if (text.length() > 400) {
                    text = text.substring(0, 400) + "...";
                }
                System.out.println("page-" + (page + 1) + "-text=" + text);
            }
        }
    }
}
