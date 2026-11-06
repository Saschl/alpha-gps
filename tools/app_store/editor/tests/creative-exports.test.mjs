import test from 'node:test';
import assert from 'node:assert/strict';
import JSZip from 'jszip';
import sharp from 'sharp';
import {selectExportTargets,creativeTargets} from '../export-targets.mjs';
import {validateCreativeBundle} from '../creative-bundle.mjs';

test('creative export selection leaves the default screenshot workflow intact',()=>{
 assert.equal(selectExportTargets().length,4);
 assert.equal(selectExportTargets({scope:'creatives'}).length,3);
 assert.equal(selectExportTargets({scope:'all'}).length,7);
 assert.equal(selectExportTargets({device:'creative-header'})[0].key,'creative-header');
 assert.throws(()=>selectExportTargets({device:'typo'}),/Unknown export device/);
 assert.throws(()=>selectExportTargets({scope:'typo'}),/Unknown export scope/);
 assert.deepEqual(creativeTargets.find(t=>t.key==='creative-search').sizes,[[3840,2560],[1920,1280]]);
});
const target={key:'creative-header',sizes:[[12,8]]};
async function bundle({locale='en',key='creative-header',width=12,alpha=false}={}){
 const zip=new JSZip();
 zip.file(`ios/${key}/12x8/${locale}/01-hero.png`,await sharp({create:{width,height:8,channels:alpha?4:3,background:'#abcdef'}}).png().toBuffer());
 return zip.generateAsync({type:'nodebuffer'});
}
test('accepts a valid opaque creative image',async()=>{
 const result=await validateCreativeBundle(await bundle(),target,['en'],1);
 assert.equal(result.length,1);
});
test('rejects wrong-slot exports even if stale correct files exist on disk',async()=>{
 await assert.rejects(validateCreativeBundle(await bundle({key:'creative-search'}),target,['en'],1),/Wrong placement/);
});
test('rejects missing localization, dimensions and alpha channels',async()=>{
 await assert.rejects(validateCreativeBundle(await bundle(),target,['en','de'],1),/expected 2/);
 await assert.rejects(validateCreativeBundle(await bundle({width:10}),target,['en'],1),/Invalid image/);
 await assert.rejects(validateCreativeBundle(await bundle({alpha:true}),target,['en'],1),/Invalid image/);
});
