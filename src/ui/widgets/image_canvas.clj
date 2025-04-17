(ns ui.widgets.image-canvas
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.graphics Font FontData Image]
           [org.eclipse.swt.widgets Display Canvas]
           [org.eclipse.swt.layout FillLayout]))



#_(on-paint-control [props e]
                  (let [image-canvas (:ui/quickref-overview @props)
                        canvas-size (.getSize image-canvas)
                        canvas-width (.x canvas-size)
                        width-ratio (/ canvas-width quickref-image-width)
                        canvas-height (.y canvas-size)
                        height-ratio (/ canvas-height quickref-image-height)

                        scale-image-height-by-width (int (* quickref-image-height width-ratio))
                        scale-image-width-by-height (int (* quickref-image-width height-ratio))]
                    (with-gc-on image-canvas
                      (fn [gc]
                        (doto gc
                          (.setAntialias SWT/ON)
                          (.setInterpolation SWT/HIGH))
                        (if (>= scale-image-height-by-width canvas-height)
                          (.drawImage gc quickref-image
                                      0 0 quickref-image-width quickref-image-height
                                      0 0 scale-image-width-by-height canvas-height)
                          (.drawImage gc quickref-image
                                      0 0 quickref-image-width quickref-image-height
                                      0 0 canvas-width scale-image-height-by-width))))))


(defn svg-image
  ;; https://linuxtut.com/edit-svg-with-java-+-apache-batik-to-convert-to-png-or-jpeg-f243e/
  "Return an Image from SVG file.  Options are :width x :height x; or :size [w h]; :keep-aspect-ratio true/false"
  [svg-file-path & opts])


(defn image-canvas
  [image-file-path & inits]
  (let [image (Image. (Display/getDefault) image-file-path)]
    ;; Something like:
    #_(canvas SWT/DOUBLE_BUFFERED
              (on-paint [props event]
                        ;; Scale image example https://www.aniszczyk.org/2007/08/09/resizing-images-using-swt/
                        )
              (widget-disposed [props event] (.dipose image)))))
