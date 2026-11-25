#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "zdtun.h"
static int sink(zdtun_t *tun, zdtun_pkt_t *packet, const zdtun_conn_t *conn) { return 0; }
static uint32_t seed = 0xCC30500;
static uint32_t next(void) { seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5; return seed; }
int main(void) {
 zdtun_callbacks_t callbacks = {.send_client = sink};
 zdtun_t *tun = zdtun_init(&callbacks, NULL);
 if (!tun) return 2;
 unsigned accepted=0;
 for (unsigned iteration=0; iteration<100000; iteration++) {
  unsigned length=next()%2049;
  uint8_t *data=malloc(length ? length : 1);
  if (!data) return 3;
  for (unsigned i=0;i<length;i++) data[i]=(uint8_t)next();
  if (length>=1 && iteration%3==0) data[0]=0x45;
  if (length>=1 && iteration%3==1) data[0]=0x60;
  if (length>=40 && iteration%4==0) {data[9]=17;data[6]=data[7]=0;}
  if (length>=60 && iteration%4==1) {data[6]=6;data[52]=0x50;}
  if (length>=40 && iteration%4==2) data[6]=17;
  zdtun_pkt_t packet;
  int result=zdtun_parse_pkt(tun,(const char*)data,(uint16_t)length,&packet);
  if (result==0) {
   accepted++;
   if ((char*)packet.l7<(char*)data || (char*)packet.l7+packet.l7_len>(char*)data+length) abort();
  }
  free(data);
 }
 zdtun_finalize(tun);
 printf("100000 deterministic malformed packet parses; %u accepted; no forwarding or sockets\n",accepted);
 return 0;
}
