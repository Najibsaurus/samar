<li class="entityFacet tag">${entity.title} <a href="#"
 class="removeEntityLink" entity="${entity.id}">x</a></li>
<div class="entityTooltip">
    <div class="entityDepiction">
      <#if entity.entity.depiction.filename?has_content>
      <img src="${This.bigFileUrl(entity, 'entity:depiction', '')}" />
      <#else>
      <img src="${This.baseURL}icons/${entity.type}_100.png" />
      </#if>
    </div>
    <div class="entitySummary">
      <h3 dir="auto"><a href="${This.getBackofficeURL(entity)}">${entity.title}</a></h3>
      <p class="ellipsis" dir="auto">${entity.entity.summary}</p>
    </div>
    <div style="clear: both"></div>
</div>
