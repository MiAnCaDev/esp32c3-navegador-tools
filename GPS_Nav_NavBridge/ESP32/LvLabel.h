#pragma once
/*
  Rendimiento: lv_label_set_text() repinta la etiqueta aunque el texto sea el mismo. Muchas etiquetas se refrescan
  cada segundo (velocidad, distancia, bateria, lactancia...), asi que aqui se ignoran las llamadas que no cambian nada.

  Esta en un .h (y no dentro del .ino) a proposito: el IDE de Arduino genera los prototipos de las funciones justo
  antes de la PRIMERA funcion del .ino; si esa funcion estuviera antes de los tipos y de <ArduinoJson.h> el sketch
  no compilaria. Debe incluirse ANTES de LactanciaApp.h para que tambien le afecte.
*/
#include <string.h>
#include <lvgl.h>

static inline void lvLabelSetIfChanged(lv_obj_t *obj, const char *txt)
{
  const char *cur = lv_label_get_text(obj);
  if (cur != nullptr && txt != nullptr && strcmp(cur, txt) == 0) return;
  lv_label_set_text(obj, txt);          // aqui todavia es la funcion real (la macro se define abajo)
}
#define lv_label_set_text lvLabelSetIfChanged
