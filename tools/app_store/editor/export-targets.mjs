export const screenshotTargets = [
  {key:'iphone',label:'iPhone'}, {key:'ipad',label:'iPad'},
  {key:'duo-outer',label:'iPhone Duo outer'},
  {key:'duo-inner-landscape',label:'iPhone Duo inner'},
];
export const creativeTargets = [
  {key:'creative-universal',label:'Universal creative',sizes:[[5244,2950]]},
  {key:'creative-header',label:'Product page header',sizes:[[3840,1646]]},
  {key:'creative-search',label:'Search results',sizes:[[3840,2560],[1920,1280]]},
];
export function selectExportTargets({scope='screenshots',device}={}) {
  const all=[...screenshotTargets,...creativeTargets];
  if(device){
    const target=all.find(t=>t.key===device);
    if(!target)throw new Error(`Unknown export device: ${device}`);
    return [target];
  }
  const groups={screenshots:screenshotTargets,creatives:creativeTargets,all};
  if(!groups[scope])throw new Error(`Unknown export scope: ${scope}`);
  return groups[scope];
}
