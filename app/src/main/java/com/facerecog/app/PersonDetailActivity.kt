package com.facerecog.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.facerecog.app.databinding.ActivityPersonDetailBinding
import com.facerecog.app.databinding.ItemFaceThumbBinding
import kotlinx.coroutines.launch

class PersonDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonDetailBinding
    private lateinit var db: AppDatabase
    private var personId: Long = -1
    private lateinit var faceAdapter: FaceThumbAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPersonDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        personId = intent.getLongExtra("personId", -1)
        val personName = intent.getStringExtra("personName") ?: "Person"
        supportActionBar?.title = personName
        binding.tvPersonName.text = personName

        db = AppDatabase.getInstance(this)

        faceAdapter = FaceThumbAdapter { embedding ->
            AlertDialog.Builder(this)
                .setTitle("Remove this photo?")
                .setPositiveButton("Remove") { _, _ ->
                    lifecycleScope.launch {
                        db.personDao().deleteEmbedding(embedding)
                        loadFaces()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.recyclerFaces.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerFaces.adapter = faceAdapter

        binding.btnRename.setOnClickListener { showRenameDialog(personName) }
        binding.btnDeletePerson.setOnClickListener { deletePerson() }

        loadFaces()
    }

    private fun loadFaces() {
        lifecycleScope.launch {
            val faces = db.personDao().getEmbeddingsForPerson(personId)
            faceAdapter.submitList(faces)
            binding.tvCount.text = "${faces.size} saved photo(s)"
        }
    }

    private fun showRenameDialog(currentName: String) {
        val input = EditText(this)
        input.setText(currentName)
        AlertDialog.Builder(this)
            .setTitle("Rename person")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        val person = db.personDao().getPersonById(personId)
                        if (person != null) {
                            db.personDao().updatePerson(person.copy(name = newName))
                            supportActionBar?.title = newName
                            binding.tvPersonName.text = newName
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePerson() {
        AlertDialog.Builder(this)
            .setTitle("Delete this person?")
            .setMessage("This removes all their saved face data.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val person = db.personDao().getPersonById(personId)
                    db.personDao().deleteEmbeddingsForPerson(personId)
                    if (person != null) db.personDao().deletePerson(person)
                    Toast.makeText(this@PersonDetailActivity, "Deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class FaceThumbAdapter(
    private val onLongPressDelete: (FaceEmbeddingEntity) -> Unit
) : androidx.recyclerview.widget.ListAdapter<FaceEmbeddingEntity, FaceThumbAdapter.VH>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<FaceEmbeddingEntity>() {
        override fun areItemsTheSame(a: FaceEmbeddingEntity, b: FaceEmbeddingEntity) = a.id == b.id
        override fun areContentsTheSame(a: FaceEmbeddingEntity, b: FaceEmbeddingEntity) = a == b
    }
) {
    inner class VH(val binding: ItemFaceThumbBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val binding = ItemFaceThumbBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        if (!item.imagePath.isNullOrEmpty()) {
            Glide.with(holder.itemView).load(item.imagePath).into(holder.binding.imgFace)
        }
        holder.binding.root.setOnLongClickListener {
            onLongPressDelete(item)
            true
        }
    }
}
