import JSZip from 'jszip';
import sharp from 'sharp';

export async function validateCreativeBundle(bytes,target,locales,slideCount){
  if(!locales.length || slideCount<1)throw new Error(`No localized slides for ${target.key}`);
  const zip=await JSZip.loadAsync(bytes);
  const files=Object.values(zip.files).filter(f=>!f.dir);
  const expected=target.sizes.length*locales.length*slideCount;
  if(files.length!==expected)throw new Error(`${target.key}: expected ${expected} images, got ${files.length}`);
  const assets=[];
  for(const [width,height] of target.sizes)for(const locale of locales){
    const prefix=`ios/${target.key}/${width}x${height}/${locale}/`;
    const group=files.filter(f=>f.name.startsWith(prefix)&&/^\d{2}-[^/]+\.png$/.test(f.name.slice(prefix.length)));
    if(group.length!==slideCount)throw new Error(`Wrong placement, size or locale: ${prefix}`);
    for(const file of group){
      const data=await file.async('nodebuffer');
      const m=await sharp(data).metadata();
      if(m.format!=='png'||m.width!==width||m.height!==height||m.hasAlpha||m.space!=='srgb')
        throw new Error(`Invalid image: ${file.name}; expected opaque sRGB ${width}x${height}`);
      assets.push({file:file.name,device:target.key,locale,width,height,data});
    }
  }
  return assets;
}
