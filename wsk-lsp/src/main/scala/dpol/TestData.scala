package dpol

import scala.scalanative.unsafe.*
import _root_.treesitter.types.uint32_t
import scala.scalanative.libc.string.strlen

object TestData:

  // TODO empty field description breaks this query
  val configSchemaDefinitionQuery: String = """(pair 
  key: (string ((string_content) @string (#eq? @string "configSchema")))
  value: (object (pair 
                   key: (string (string_content) @config_schema_variable)
                   value: (object (pair 
                                    key: (string (string_content) @field_description_key (#eq? @field_description_key "fieldDescription"))
                                    value: (string (string_content) @field_description)
                                    )) 
                   ))
)"""

  val idAndNameRequestQuery: String = """(pair 
  key: (string (string_content) @requests_key (#eq? @requests_key "requests"))
  value: (array (object (pair value: (object 
                                       (pair key: (string (string_content) @id_key (#eq? @id_key "id")) value: (string (string_content) @id_value)) 
                                       (pair key: (string (string_content) @name_key (#eq? @name_key "name")) value: (string (string_content) @name_value)) 
                                             ) @request)) )
)"""

  val nameAndIdRequestQuery: String = """(pair 
  key: (string (string_content) @requests_key (#eq? @requests_key "requests"))
  value: (array (object (pair value: (object 
                                       (pair key: (string (string_content) @name_key (#eq? @name_key "name")) value: (string (string_content) @name_value)) 
                                       (pair key: (string (string_content) @id_key (#eq? @id_key "id")) value: (string (string_content) @id_value)) 
                                             ) @request)) )
)"""

  val errorQuery: String = """(ERROR) @error"""
